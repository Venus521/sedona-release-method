package com.venus.releaseapp;

import java.util.ArrayList;
import java.util.Map;

/**
 * 云端内容包清单（/app/web.json）的解析与判定 —— 「不重装 APK 就换界面」的那半条腿。
 *
 * 和 UpdateInfo 一样刻意只用 JDK：不碰 org.json、不碰 android.*，同一份文件能在电脑上
 * 原样编译、原样跑断言（门禁 W01~W12 跑的就是手机上跑的这段字节）。
 *
 * files 塞成一个字符串：完整地址|sha前缀8位|字节数，条目间以 ; 分隔。
 * 存完整地址而不是相对名，是为了让「校验前先看地址」这条闸口有东西可校验；
 * 清单只有一层扁平对象是前面定死的规矩，这里不为了三行表开嵌套数组的口子。
 */
public class WebInfo {

    public static final String MANIFEST = "https://" + UpdateInfo.HOST + "/apprel/web.json";
    /**
     * 内容包地址前缀，生产只有一个：发布桶下按 code 分目录。
     * 桌面门禁可以把它指到本机假服务器（W 组断言要真发 HTTP 才算跑通整条下载链），
     * 手机上没人改它，所以闸口没有因此松动。
     */
    public static String BASE = "https://" + UpdateInfo.HOST + "/apprel/web/";

    public final int code;
    public final String notes;
    /** 每项三个字段：path / sha256 前缀（小写）/ 字节数，顺序恒定。 */
    public final ArrayList<String[]> files;

    private WebInfo(int code, String notes, ArrayList<String[]> files) {
        this.code = code;
        this.notes = notes;
        this.files = files;
    }

    public static WebInfo parse(String text) throws Exception { return parse(text, BASE); }

    /** 任何一处不合要求都抛，让调用方只能选「不更新」，绝不会半套覆盖内置副本。
     *  base 显式传入：坚果云货架与 CloudBase 货架共用这份清单格式，各自钉各自的地址白名单。 */
    public static WebInfo parse(String text, String base) throws Exception {
        Map<String, String> f = UpdateInfo.flatJson(text);
        int code = intValue(f.get("code"));
        if (code <= 0) throw new Exception("code 缺失或不合法");
        String spec = f.get("files");
        if (spec == null || spec.trim().length() == 0) throw new Exception("files 缺失");
        // 一条一条判，而不是拿整串过一次正则：条目之间本来就可能带空格，整串正则要么误杀
        // 合法写法（发布器多打一个空格就整包作废），要么放过脏写法（分隔符歪了被静默跳过）。
        // limit=-1 是为了让尾随的那个分号露出来 —— 默认切法会把尾随空串直接吞掉。
        String[] entries = spec.split(";", -1);
        ArrayList<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < entries.length; i++) {
            String e = entries[i].trim();
            if (e.length() == 0) throw new Exception("files 分隔符写歪了（有空条目）：" + spec);
            String[] seg = e.split("\\|");
            if (seg.length != 3) throw new Exception("files 条目不是「地址|校验|大小」三段：" + e);
            String url = seg[0].trim();
            String sha = seg[1].trim().toLowerCase();
            if (!url.matches("^[A-Za-z0-9._/:|-]+$")) throw new Exception("地址里有非法字符：" + url);
            if (!isSafePath(url, base)) throw new Exception("下载地址不在白名单内：" + url);
            // 落盘名取 base+code/ 后面那段，斜杠照原样建子目录（vendor/ 就是这么进来的）
            String want = base + code + "/";
            if (!url.startsWith(want)) throw new Exception("地址不在本内容包的 code 目录下：" + url);
            String rel = url.substring(want.length());
            if (rel.length() == 0 || rel.startsWith("/") || rel.indexOf('\\') >= 0) {
                throw new Exception("文件路径不合法：" + rel);
            }
            for (int j = 0; j < out.size(); j++) {
                if (out.get(j)[0].equals(rel)) throw new Exception("同一个文件在 files 里出现两次：" + rel);
            }
            if (!sha.matches("^[0-9a-f]{8}$")) throw new Exception("校验前缀不是 8 位十六进制：" + e);
            long size = sizeOf(seg[2]);
            if (size <= 0) throw new Exception("字节数不合法：" + e);
            out.add(new String[]{rel, sha, String.valueOf(size)});
        }
        if (out.isEmpty()) throw new Exception("files 里一个条目都没有");
        return new WebInfo(code, f.get("notes") == null ? "" : f.get("notes"), out);
    }

    private static long sizeOf(String raw) throws Exception {
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            throw new Exception("字节数读不出来：" + raw);
        }
    }

    private static int intValue(String raw) throws Exception {
        if (raw == null) throw new Exception("code 缺失");
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            throw new Exception("code 不是整数：" + raw);
        }
    }

    /** 必须落在内容包目录下，且只有安全字符——清单说不了别的机器上的文件。 */
    public static boolean isSafePath(String path) { return isSafePath(path, BASE); }

    public static boolean isSafePath(String path, String base) {
        if (path == null) return false;
        if (!path.startsWith(base) || path.contains("..")) return false;
        return path.substring(base.length()).matches("^[A-Za-z0-9._/-]+$");
    }

    /** 清单里存的就是完整地址，这里只负责把它拼出来给发布器和断言用。 */
    public static String urlFor(int code, String relPath) { return urlFor(BASE, code, relPath); }

    public static String urlFor(String base, int code, String relPath) {
        return base + code + "/" + relPath;
    }

    public boolean isNewerThan(int current) {
        return code > current;
    }

    public long totalBytes() {
        long t = 0;
        for (int i = 0; i < files.size(); i++) {
            t += Long.parseLong(files.get(i)[2]);
        }
        return t;
    }

    public int totalKb() {
        return (int) Math.max(1, totalBytes() / 1024);
    }
}
