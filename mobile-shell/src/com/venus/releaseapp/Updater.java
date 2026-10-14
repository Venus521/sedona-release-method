package com.venus.releaseapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App 内一键更新：从云端清单拿新版 → 下载校验 → 拉起系统安装器。
 *
 * 全程只走公网那一个发布域名，和家里电脑开不开机无关；清单解析与地址白名单都交给
 * UpdateInfo（同一份代码在电脑上门禁里跑过 22 条断言），这里只管搬运和确认。
 * 一次只允许一条流水线在跑（RUNNING），因为「弹两个更新框」比「慢半拍」更招人烦。
 */
public class Updater {

    /** 结果只有一条通道：手动检查回页面，静默检查由调用方给 null 就什么都不说。 */
    public interface Ui {
        void onResult(String text, boolean ok);
    }

    private static final long MAX_MANIFEST = 64 * 1024;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final String PREF = "shell";
    private static final String SKIP_KEY = "skip_code";

    public static int currentCode(Activity ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentName(Activity ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * @param manual 人在页面上按了「检查更新」：没新版也要回一句话。
     *               静默检查只有真找到新版才开口，免得每次开 App 都被网络状况轰炸。
     */
    public static void start(final Activity ctx, final boolean manual, final Ui ui) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (manual && ui != null) ui.onResult("已经有一个更新在跑了，稍等一下", false);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                UpdateInfo info;
                try {
                    info = UpdateInfo.parse(fetchManifest());
                } catch (Exception e) {
                    stop(ui, manual, "检查更新失败：" + why(e), false);
                    return;
                }
                int cur = currentCode(ctx);
                if (!info.isNewerThan(cur)) {
                    stop(ui, manual, "已经是最新版 v" + currentName(ctx), true);
                    return;
                }
                if (!manual && skipped(ctx) == info.versionCode) {
                    RUNNING.set(false);   // 这个版本人已经说过「以后再说」，别再拦路
                    return;
                }
                confirm(ctx, info, cur, manual, ui);
            }
        }).start();
    }

    /** 之后的每一步（下载、校验、装）都是人点了「立即更新」才发生，所以一律要报结果。 */
    private static void confirm(final Activity ctx, final UpdateInfo info, final int cur,
                               final boolean manual, final Ui ui) {
        ctx.runOnUiThread(new Runnable() {
            public void run() {
                if (ctx.isFinishing()) {
                    RUNNING.set(false);
                    return;
                }
                String body = "现在装的是 v" + currentName(ctx) + "（" + cur + "），可以升到 "
                        + info.describe() + "。"
                        + (info.notes.length() > 0 ? "\n\n" + info.notes : "")
                        + "\n\n下载完会在系统安装器里确认，进度和片单都不会动。";
                new AlertDialog.Builder(ctx)
                        .setTitle("发现新版本 v" + info.versionName)
                        .setMessage(body)
                        .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                download(ctx, info, ui);
                            }
                        })
                        .setNegativeButton("以后再说", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (!manual) setSkip(ctx, info.versionCode);
                                stop(ui, manual, "先不更新", true);
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (!manual) setSkip(ctx, info.versionCode);
                                RUNNING.set(false);
                            }
                        })
                        .show();
            }
        });
    }

    private static void download(final Activity ctx, final UpdateInfo info, final Ui ui) {
        new Thread(new Runnable() {
            public void run() {
                say(ui, "正在下载 v" + info.versionName + "（" + info.describe() + "）…");
                File f;
                try {
                    f = downloadFile(ctx, info);
                } catch (Exception e) {
                    stop(ui, true, "下载没成功：" + why(e), false);
                    return;
                }
                RUNNING.set(false);
                install(ctx, f, info, ui);
            }
        }).start();
    }

    private static void install(final Activity ctx, final File f, final UpdateInfo info, final Ui ui) {
        if (Build.VERSION.SDK_INT >= 26 && !canInstall(ctx)) {
            askPermission(ctx, info, ui);
            return;
        }
        ctx.runOnUiThread(new Runnable() {
            public void run() {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(UpdateProvider.uriFor(f),
                        "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                    if (ui != null) ui.onResult("已交给你系统安装器，点「安装」就完事", true);
                } catch (Exception e) {
                    if (ui != null) ui.onResult("打不开安装器：" + why(e), false);
                }
            }
        });
    }

    /** API 26+ 装 APK 是单独一条授权，只能人自己在系统设置里开；一键跳过去，别让人翻菜单。 */
    private static void askPermission(final Activity ctx, final UpdateInfo info, final Ui ui) {
        ctx.runOnUiThread(new Runnable() {
            public void run() {
                if (ctx.isFinishing()) return;
                new AlertDialog.Builder(ctx)
                        .setTitle("要允许释放法安装应用")
                        .setMessage("App 内更新需要这个权限。下一步会打开系统设置里释放法这一项，"
                                + "把「信任此应用／允许安装未知应用」打开，回来再点一次「检查更新」就行。")
                        .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                try {
                                    ctx.startActivity(new Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:" + ctx.getPackageName())));
                                    say(ui, "设置里打开开关后，再点一次「检查更新」");
                                } catch (Exception e) {
                                    say(ui, "这个系统没给 App 内更新的开关：" + why(e));
                                }
                            }
                        })
                        .setNegativeButton("算了", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                say(ui, "那这次先不更新");
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                            }
                        })
                        .show();
            }
        });
    }

    private static boolean canInstall(Activity ctx) {
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------- 网络与落盘 ---------- */

    static String fetchManifest() throws Exception {
        // 清单是唯一的可变文件，取的时候打散 CDN 缓存：卡在旧清单上会让人一直以为「已是最新版」。
        // 包本身按 versionCode 命名（AniTracker-4.apk），内容永不变，反而不需要这手。
        return Net.readText(UpdateInfo.MANIFEST + "?_=" + System.currentTimeMillis(), MAX_MANIFEST);
    }

    /** 先下到 .part 再改名：中途断网只会留下半截 .part，绝不会让安装器捡到不完整的包。 */
    static File downloadFile(Activity ctx, UpdateInfo info) throws Exception {
        File dir = UpdateProvider.dir(ctx);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.isFile()) f.delete();
            }
        }
        File tmp = new File(dir, "AniTracker-" + info.versionCode + ".apk.part");
        File out = new File(dir, "AniTracker-" + info.versionCode + ".apk");
        HttpURLConnection c = Net.open(info.url);
        FileOutputStream fo = null;
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("下载被拒 HTTP " + code);
            long declared = c.getContentLength();
            if (declared > 0 && declared != info.size) {
                throw new Exception("云端报的大小和清单不符（" + declared + " ≠ " + info.size + "）");
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = c.getInputStream();
            fo = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > info.size) throw new Exception("下载超出清单大小，已中止");
                md.update(buf, 0, n);
                fo.write(buf, 0, n);
            }
            fo.flush();
            fo.close();
            fo = null;
            in.close();
            in = null;
            if (total != info.size) throw new Exception("只下到 " + total + " 字节，清单要 " + info.size);
            if (!Net.hex(md.digest()).equals(info.sha256)) {
                throw new Exception("校验和不符，包不完整，已丢弃");
            }
            if (out.exists() && !out.delete()) throw new Exception("旧包删不掉");
            if (!tmp.renameTo(out)) throw new Exception("下载文件改名失败");
            return out;
        } finally {
            if (fo != null) try { fo.close(); } catch (Exception ignore) { }
            if (in != null) try { in.close(); } catch (Exception ignore) { }
            tmp.delete();
            c.disconnect();
        }
    }

    /* ---------- 状态与开口 ---------- */

    private static int skipped(Activity ctx) {
        return prefs(ctx).getInt(SKIP_KEY, -1);
    }

    private static void setSkip(Activity ctx, int code) {
        prefs(ctx).edit().putInt(SKIP_KEY, code).apply();
    }

    private static SharedPreferences prefs(Activity ctx) {
        return ctx.getSharedPreferences(PREF, Activity.MODE_PRIVATE);
    }

    /** 收工并放行下一条：只有 manual（人主动按的）才需要回话。 */
    private static void stop(Ui ui, boolean manual, String msg, boolean ok) {
        RUNNING.set(false);
        if (manual && ui != null) ui.onResult(msg, ok);
    }

    private static void say(Ui ui, String msg) {
        if (ui != null) ui.onResult(msg, true);
    }

    /** 手机上看不见栈，异常自带的说明就是全部线索（没说明时至少给个类名，别说「失败：null」）。 */
    private static String why(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }
}
