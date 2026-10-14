package com.venus.releaseapp;

import java.util.HashMap;
import java.util.Map;

/**
 * 云端更新清单（app/version.json）的解析与判定。
 *
 * 故意只用 JDK：不碰 org.json、不碰 android.*。这样同一份文件能在电脑上原样编译、
 * 原样跑断言——测试里跑的就是手机上跑的那段代码，而不是某个替身。
 * 清单是我们自己生成的扁平对象，所以这里自带一个只认扁平对象的小解析器，
 * 认不出的输入一律抛异常，绝不"尽力解析"出一个半对的版本号。
 */
public class UpdateInfo {

    /** 发布桶锁死：只认 CloudBase 静态托管这一个域名下的 /app/*.apk。 */
    public static final String HOST = "cloud1-d7gsn5t0w6407b963-1460816419.tcloudbaseapp.com";
    public static final String MANIFEST = "https://" + HOST + "/apprel/version.json";

    public final int versionCode;
    public final String versionName;
    public final String url;
    public final long size;
    public final String sha256;
    public final String notes;

    private UpdateInfo(int code, String name, String url, long size, String sha, String notes) {
        this.versionCode = code;
        this.versionName = name;
        this.url = url;
        this.size = size;
        this.sha256 = sha;
        this.notes = notes;
    }

    /** 任何一处不合要求都抛，让调用方只能选"不更新"，不会误装。 */
    public static UpdateInfo parse(String text) throws Exception {
        Map<String, String> f = flatJson(text);
        int code = intValue(f.get("versionCode"), 0);
        long size = longValue(f.get("size"), 0);
        String name = stringValue(f.get("versionName"));
        String url = stringValue(f.get("url"));
        String sha = stringValue(f.get("sha256")).toLowerCase();
        String notes = stringValue(f.get("notes"));
        if (code <= 0) throw new Exception("versionCode 缺失或不合法");
        if (name.length() == 0) throw new Exception("versionName 缺失");
        if (size <= 0) throw new Exception("size 缺失或不合法");
        if (!sha.matches("^[0-9a-f]{64}$")) throw new Exception("sha256 不是 64 位十六进制");
        if (!isSafeDownload(url)) throw new Exception("下载地址不在白名单内");
        return new UpdateInfo(code, name, url, size, sha, notes);
    }

    /** https + 域名精确相等 + 路径在 /apprel/ 下 + 以 .apk 结尾，四样缺一不可。
     *  （前缀必须与发布端那条 /apprel/ 完全一致：写回 /app/ 的话，云端清单里的地址
     *  一条也过不了这道闸，手机上只会看到「检查更新失败」。） */
    public static boolean isSafeDownload(String url) {
        if (url == null) return false;
        if (!url.startsWith("https://")) return false;
        String rest = url.substring("https://".length());
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);   // user@host 那种花招只看 host
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        if (!authority.equalsIgnoreCase(HOST)) return false;
        String path = slash < 0 ? "" : rest.substring(slash);
        int cut = path.length();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '?' || c == '#') { cut = i; break; }
        }
        String pure = path.substring(0, cut);
        return pure.startsWith("/apprel/") && pure.endsWith(".apk") && !pure.contains("..");
    }

    public boolean isNewerThan(int current) {
        return versionCode > current;
    }

    public String describe() {
        return "v" + versionName + " · " + Math.max(1, size / 1024) + " KB";
    }

    /* ---------- 扁平 JSON 小解析器 ---------- */

    /** 只支持 {"k":v,...} 一层；v 限字符串/数字/true/false/null。遇到数组对象等直接抛。 */
    public static Map<String, String> flatJson(String s) throws Exception {
        Map<String, String> out = new HashMap<String, String>();
        int i = skipWs(s, 0);
        if (i >= s.length() || s.charAt(i) != '{') throw new Exception("清单不是 JSON 对象");
        i++;
        // 状态机：0=刚开括号（可收可续） 1=刚读完值（要逗号或收） 2=刚吃完逗号（必须再来一个键）
        int st = 0;
        while (true) {
            i = skipWs(s, i);
            if (i >= s.length()) throw new Exception("JSON 被截断");
            char c = s.charAt(i);
            if (c == '}') {
                if (st == 2) throw new Exception("不允许尾逗号");
                return out;
            }
            if (c == ',') {
                if (st != 1) throw new Exception("逗号位置不对");
                st = 2;
                i++;
                continue;
            }
            if (st == 1) throw new Exception("缺逗号");   // st==2 是逗号后正要读键，合法
            int[] kend = new int[1];
            String key = readString(s, i, kend);
            i = skipWs(s, kend[0]);
            if (i >= s.length() || s.charAt(i) != ':') throw new Exception("键后面缺冒号");
            i = skipWs(s, i + 1);
            if (i >= s.length()) throw new Exception("JSON 被截断");
            int[] vend = new int[1];
            out.put(key, readValue(s, i, vend));
            i = vend[0];
            st = 1;
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String readString(String s, int from, int[] end) throws Exception {
        if (from >= s.length() || s.charAt(from) != '"') throw new Exception("这里该是个字符串");
        StringBuilder b = new StringBuilder();
        int i = from + 1;
        while (true) {
            if (i >= s.length()) throw new Exception("字符串没闭合");
            char c = s.charAt(i);
            if (c == '"') { end[0] = i + 1; return b.toString(); }
            if (c == '\\') {
                if (i + 1 >= s.length()) throw new Exception("转义断了");
                char n = s.charAt(i + 1);
                switch (n) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 5 >= s.length()) throw new Exception("\\u 断了");
                        b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 4;
                        break;
                    default: throw new Exception("不认识的转义 \\" + n);
                }
                i += 2;
            } else {
                b.append(c);
                i++;
            }
        }
    }

    /** 值原样存文本（含引号与否由 readString 决定），类型判断留给取值时。 */
    private static String readValue(String s, int from, int[] end) throws Exception {
        char c = s.charAt(from);
        if (c == '"') return readString(s, from, end);
        if (c == '{' || c == '[') throw new Exception("清单只支持一层扁平对象");
        int i = from;
        while (i < s.length()) {
            char x = s.charAt(i);
            if (x == ',' || x == '}' || Character.isWhitespace(x)) break;
            i++;
        }
        if (i == from) throw new Exception("空值");
        String raw = s.substring(from, i);
        if (raw.equals("true") || raw.equals("false") || raw.equals("null")) { end[0] = i; return "null"; }
        if (!raw.matches("-?[0-9]+(\\.[0-9]+)?")) throw new Exception("认不出的值：" + raw);
        end[0] = i;
        return raw;
    }

    private static String stringValue(String raw) {
        return raw == null ? "" : raw;
    }

    private static int intValue(String raw, int def) {
        try { return raw == null ? def : Integer.parseInt(raw); } catch (Exception e) { return def; }
    }

    private static long longValue(String raw, long def) {
        try { return raw == null ? def : Long.parseLong(raw); } catch (Exception e) { return def; }
    }
}
