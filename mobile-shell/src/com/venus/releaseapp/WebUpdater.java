package com.venus.releaseapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内容热更：把云端那份释放法下到手机私有目录，下次打开页面就用它，不碰 APK。
 *
 * 为什么需要它：网页整个打进 APK 是为了「电脑关机也能用」，代价是每改一行界面就得
 * 重装一次包。装上这层之后 APK 退化成「自带兜底副本的播放器」，日常换界面只发内容。
 *
 * 规矩三条：
 *   1) 整包才算一次 —— 任何一个文件下不全或校验不上，整包作废继续用旧的，
 *      绝不让 index.html 和它引用的 js 来自两个版本（那正是白屏的配方）；
 *   2) 落盘进 code 独立目录，指针另存 —— 换砸了只是没人引用那个目录，删掉即可；
 *   3) 只走 https 的发布桶，地址由清单里的 code 推出，清单说不了假地址。
 */
public class WebUpdater {

    public interface Ui {
        void onResult(String text, boolean ok);
    }

    private static final String PREF = "shell";
    private static final String CODE_KEY = "web_code";
    /** 这份内容是在哪个 APK 版本下下载的 —— APK 一升级就必须作废，见 currentRoot。 */
    private static final String APK_KEY = "web_apk";
    private static final long MAX_MANIFEST = 64 * 1024;
    /** 单个文件留头：vendor 里那份 SDK 840KB，一 MB 的宽度是给日后留的，不是用来兜漏洞的。 */
    private static final long MAX_FILE = 6 * 1024 * 1024;
    /** 全套的总量闸：清单被换过也只能让手机多写这么多，写不进磁盘死角。 */
    private static final long MAX_TOTAL = 24 * 1024 * 1024;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** 本次进程已经引用上的内容目录，null 表示用 APK 内置那份。 */
    private static volatile File applied;

    /** 内容根目录：装的是各个 code 的完整网页包。没权限的机器上退回私有目录，反正都读得到。 */
    public static File contentRoot(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        File base = ext != null ? ext : ctx.getFilesDir();
        return new File(base, "content");
    }

    /**
     * 当前该用哪一份内容：指针指向的目录必须真有 index.html，否则退回内置。
     *
     * 另一条更要紧：APK 一升级（换了内置那份更新的内容）就把旧热更作废。
     * 不然人刚装上新版 App，页面却还被上一次下载的老内容盖着——装了个更新的反而更旧。
     */
    public static File currentRoot(Context ctx) {
        File cached = applied;
        if (cached != null) return cached;
        SharedPreferences p = prefs(ctx);
        int apk = apkCode(ctx);
        if (p.getInt(APK_KEY, -1) != apk) {
            p.edit().remove(CODE_KEY).remove(APK_KEY).apply();
            return null;
        }
        int code = p.getInt(CODE_KEY, 0);
        if (code <= 0) return null;
        File dir = new File(contentRoot(ctx), String.valueOf(code));
        if (!new File(dir, "index.html").isFile()) return null;
        applied = dir;
        return dir;
    }

    /** 拿不到真实版本号（测试替身）时算 0，只影响「换没换代」这一条比较。 */
    static int apkCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * @param manual 人在页面上按了「检查内容更新」：没新版也要回一句。
     *               静默检查只在真换了内容时开口，免得每天开 App 都被网络状况轰炸。
     */
    /** 老入口：货架本次构建已由 WebSource.pick() 定死，这里只是保住旧调用点与旧门禁的形状。 */
    public static void run(final Context ctx, final boolean manual, final Ui ui) {
        run(ctx, manual, ui, WebSource.pick());
    }

    public static void run(final Context ctx, final boolean manual, final Ui ui, final WebSource src) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (manual && ui != null) ui.onResult("已经有一次内容更新在跑了，稍等一下", false);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    currentRoot(ctx);            // 顺手执行「APK 换代就作废旧热更」那条清理
                    String text = Net.readText(
                            src.manifestUrl + "?_=" + System.currentTimeMillis(), MAX_MANIFEST, src.auth);
                    WebInfo info = WebInfo.parse(text, src.base);
                    SharedPreferences p = prefs(ctx);
                    int cur = p.getInt(CODE_KEY, 0);
                    int apk = apkCode(ctx);
                    if (!info.isNewerThan(cur)) {
                        // 云端还没这码的内容（比如刚发过又回滚）：把指针和当前 APK 版本对齐，
                        // 免得换代清理之后，明明在用的这份被当成"没有指针"而重复下一遍
                        if (cur > 0) p.edit().putInt(APK_KEY, apk).apply();
                        done(ui, manual, "网页内容已是最新（code " + cur + "）", true);
                        return;
                    }
                    File installed = install(info, contentRoot(ctx), src.base, src.auth);
                    p.edit().putInt(CODE_KEY, info.code).putInt(APK_KEY, apk).apply();
                    applied = installed;
                    prune(contentRoot(ctx), installed);
                    // 真换了内容就开口（哪怕这次是悄悄查的）：页面上那一行还写着旧 code，
                    // 不说一声人会以为热更没生效。Toast 由调用方按 manual 决定，安静模式下不响。
                    RUNNING.set(false);
                    if (ui != null) {
                        ui.onResult("网页内容已更新到 code " + info.code + "（" + info.totalKb()
                                + " KB），下次打开 App 生效", true);
                    }
                } catch (Exception e) {
                    done(ui, manual, "内容更新没成功（" + src.label + "）：" + why(e) + "，现在这份照常用", false);
                }
            }
        }).start();
    }

    /** 老入口（CloudBase 货架形状），桌面旧断言仍可用。 */
    public static File install(WebInfo info, File root) throws Exception {
        return install(info, root, WebInfo.BASE, null);
    }

    /** 整包下载：先写进临时目录，全数校验通过才改名转正，中途任何一步失败都留不下半成品。 */
    public static File install(WebInfo info, File root, String base, String auth) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new Exception("手机建不出内容目录");
        File stage = new File(root, "stage-" + System.currentTimeMillis());
        if (!stage.mkdirs()) throw new Exception("建不出下载暂存目录");
        boolean moved = false;   // 归位成功后绝不能再碰这个路径：万一 rename 是「拷过去」而非「挪过去」，删了就等于删掉新内容
        try {
            long budget = MAX_TOTAL;
            for (int i = 0; i < info.files.size(); i++) {
                String[] e = info.files.get(i);
                String url = WebInfo.urlFor(base, info.code, e[0]);
                byte[] raw = Net.readBytes(url, Math.min(MAX_FILE, budget), auth);
                budget -= raw.length;
                if (budget <= 0) throw new Exception("内容总量超出上限");
                if (raw.length != Long.parseLong(e[2])) {
                    throw new Exception(e[0] + " 大小不符（" + raw.length + " ≠ " + e[2] + "）");
                }
                if (!Net.hex(sha(raw)).startsWith(e[1])) {
                    throw new Exception(e[0] + " 校验不上，云端这份不完整");
                }
                File out = new File(stage, e[0]);
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new Exception("建不出子目录：" + e[0]);
                }
                FileOutputStream fo = new FileOutputStream(out);
                fo.write(raw);
                fo.close();
            }
            if (!new File(stage, "index.html").isFile()) throw new Exception("内容包里没有 index.html");
            File target = new File(root, String.valueOf(info.code));
            if (target.exists()) deleteTree(target);   // 同一个 code 只可能来自旧版发布器，重来一遍
            if (!stage.renameTo(target)) throw new Exception("内容目录归位失败");
            moved = true;
            return target;
        } finally {
            if (!moved) deleteTree(stage);   // 只清扫失败的那半套，成功的已经改名转正
        }
    }

    /** 转正之后清扫：只留当前这份，别的 code 和半截暂存一律删掉，免得攒满存储。 */
    public static void prune(File root, File keep) {
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].equals(keep)) continue;
            deleteTree(kids[i]);
        }
    }

    /** 给桌面门禁用的钉子：只让测试把「本次引用哪一份」清空重算。 */
    public static void resetForTest() {
        applied = null;
    }

    private static byte[] sha(byte[] raw) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(raw);
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) deleteTree(kids[i]);
            }
        }
        f.delete();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void done(Ui ui, boolean manual, String msg, boolean ok) {
        RUNNING.set(false);
        if (manual && ui != null) ui.onResult(msg, ok);
    }

    private static String why(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }
}
