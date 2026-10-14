package com.venus.releaseapp;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 释放法手机壳：全面屏 WebView，加载**本机内置**的那份释放法。
 * 电脑关机、人在外面都能用；家里电脑在线时由内置小服务器代理取最新版。
 * 只做四件网页自己做不到 Native 的事：导出落盘（saveText 直写，不抢 blob）、导入选文件、外链跳系统浏览器、
 * 以及万一本机服务器起不来时的提示面板（面板里可改家里电脑地址）。
 */
public class MainActivity extends Activity {

    /** 家里电脑的默认基地址，只作为「上游/取最新」用，不是页面地址。 */
    static final String DEFAULT_PC = "http://192.168.0.105:8089";
    private static final String PC_KEY = "pc";
    private static final int REQ_FILES = 1001;
    /** 导出落盘目录：用户按 App 名（释放法）在「下载」里找文件，挂着别的项目名就是找不到 */
    private static final String SAVE_DIR = "释放法";
    private static final int REQ_WRITE = 1002;

    private WebView web;
    private View errPanel;
    private TextView errMsg;
    private EditText urlBox;
    private ValueCallback<Uri[]> fileCb;
    private volatile LocalServer srv;
    private volatile boolean pageOk;
    private volatile boolean updateChecked;
    private volatile boolean contentChecked;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errPanel = buildErrorPanel();
        errPanel.setVisibility(View.GONE);
        root.addView(errPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        goFullscreen(root);

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);          // 片单进度全在 localStorage，不开等于空库
        st.setDatabaseEnabled(true);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportMultipleWindows(true);     // window.open 走 onCreateWindow
        st.setJavaScriptCanOpenWindowsAutomatically(true);
        st.setAllowFileAccess(false);
        st.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Shell(), "AndroidShell");
        web.setBackgroundColor(0xFFFAF8F4);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new Down());

        askWritePermissionIfNeeded();
        startServer();
    }

    /* ---------- 全面屏 ---------- */

    /**
     * 状态栏与导航栏都让位给内容，但刘海/手势条占的那一条并不浪费——
     * 把 stable inset 变成 WebView 的上下 padding，露出的是 App 自己的纸色底，
     * 于是既铺满全屏又不会被刘海咬掉标题。
     */
    @SuppressWarnings("deprecation")
    private void goFullscreen(View root) {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);   // 窗口已建，改完必须交回去才生效
        }
        applyImmersive();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getStableInsetTop();
                int bottom = Math.max(insets.getStableInsetBottom(),
                        insets.getSystemWindowInsetBottom());
                web.setPadding(0, top, 0, bottom);
                errPanel.setPadding(dp(24), top + dp(24), dp(24), bottom + dp(24));
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();      // sticky 模式失焦后会掉回默认
    }

    /* ---------- 本机服务器 ---------- */

    private void startServer() {
        final String pc = pcBase();
        new Thread(new Runnable() {
            public void run() {
                LocalServer s;
                try {
                    // 先把上次热更下来的内容接上，再起服务器：省一次读盘，也让首屏就用新内容
                    s = LocalServer.start(MainActivity.this, pc, WebUpdater.currentRoot(MainActivity.this));
                } catch (Exception e) {
                    showFatal("手机本机服务器起不来，关掉 App 再开一次试试。");
                    return;
                }
                srv = s;
                final LocalServer fs = s;
                runOnUiThread(new Runnable() {
                    public void run() {
                        errPanel.setVisibility(View.GONE);
                        fs.retryUpstream();
                        web.loadUrl(fs.pageUrl());
                        // 白屏不该让人干等：10 秒内没起来就把地址摊出来，才报得了障
                        web.postDelayed(new Runnable() {
                            public void run() {
                                if (!pageOk) showFatal("打开超时。本机地址：" + fs.pageUrl());
                            }
                        }, 10000);
                    }
                });
                // 找电脑排在开页面之后：扫段最坏三四秒，不能拿它挡在首屏前面
                String found = LocalServer.discover(pc);
                if (found != null && !found.equals(pc)) {
                    fs.setUpstream(found);
                    savePc(found);
                }
            }
        }).start();
    }

    /**
     * 内容热更：网页层（三层里的第二层）换人，WebView 纹丝不动。
     * 已经加载的页面不会中途被抽换，所以换完只在人下次打开/下拉重载时显形。
     */
    private void applyWebRoot() {
        final LocalServer s = srv;
        if (s == null) return;
        s.setWebRoot(WebUpdater.currentRoot(this));
    }

    /** 重新加载：先给上游一次复活的机会，电脑在线就取最新代码。 */
    private void reloadPage() {
        final LocalServer s = srv;
        if (s == null) {
            startServer();
            return;
        }
        errPanel.setVisibility(View.GONE);
        applyWebRoot();
        s.retryUpstream();
        web.loadUrl(s.pageUrl());
    }

    /* ---------- 地址存取 ---------- */

    private String pcBase() {
        return getSharedPreferences("shell", MODE_PRIVATE).getString(PC_KEY, DEFAULT_PC);
    }

    private void savePc(String base) {
        getSharedPreferences("shell", MODE_PRIVATE).edit().putString(PC_KEY, base).apply();
    }

    /** 输入框里什么都能填，统一收成 scheme://host:port，缺端口补 8089。 */
    private static String normalizeBase(String raw) {
        String u = raw.trim();
        if (u.length() == 0) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        Uri p = Uri.parse(u);
        String host = p.getHost();
        if (host == null || host.length() == 0) return "";
        int port = p.getPort();
        return p.getScheme() + "://" + host + (port > 0 ? ":" + port : ":8089");
    }

    /** 与本机地址同 authority 的才算「自家页面」，留在壳里；其余交系统浏览器。 */
    private boolean isLocal(String u) {
        try {
            LocalServer s = srv;
            String mine = s == null ? null : Uri.parse(s.pageUrl()).getAuthority();
            String that = Uri.parse(u).getAuthority();
            return mine != null && mine.equals(that);
        } catch (Exception e) {
            return false;
        }
    }

    private void openExternal(String u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            toast("没有能打开该链接的应用");
        }
    }

    /* ---------- 出问题时的一块提示（本机资源自带，正常情况永远见不到） ---------- */

    private View buildErrorPanel() {
        int pad = dp(24);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setPadding(pad, pad, pad, pad);
        p.setBackgroundColor(0xFFFAF8F4);
        p.setClickable(true);

        TextView t = new TextView(this);
        t.setText("释放法打不开");
        t.setTextSize(19f);
        t.setGravity(Gravity.CENTER);
        p.addView(t);

        errMsg = new TextView(this);
        errMsg.setTextSize(13f);
        errMsg.setGravity(Gravity.CENTER);
        errMsg.setPadding(0, dp(12), 0, dp(12));
        p.addView(errMsg);

        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setHint("家里电脑地址，可留空");
        urlBox.setText(pcBase());
        urlBox.setTextSize(15f);
        p.addView(urlBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView h = new TextView(this);
        h.setText("上面这行只在电脑开机、连同一个 WiFi 时有用：填了 App 会去电脑上取最新版，"
                + "留空或连不上就用 APK 里自带的那份，功能一样。");
        h.setTextSize(12f);
        h.setPadding(0, dp(8), 0, dp(8));
        p.addView(h);

        Button go = new Button(this);
        go.setText("保存并重载");
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String base = normalizeBase(urlBox.getText().toString());
                savePc(base);
                LocalServer s = srv;
                if (s != null) s.setUpstream(base.length() == 0 ? null : base);
                reloadPage();
            }
        });
        p.addView(go, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reloadPage();
            }
        });
        p.addView(retry, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    private void showFatal(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                errMsg.setText(msg);
                errPanel.setVisibility(View.VISIBLE);
            }
        });
    }

    private FrameLayout.LayoutParams lp(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /* ---------- 导航 ---------- */

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String u = r.getUrl().toString();
            if (isLocal(u)) return false;
            openExternal(u);
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String u, android.graphics.Bitmap favicon) {
            pageOk = false;
            errPanel.setVisibility(View.GONE);
        }

        @Override
        public void onPageFinished(WebView v, String u) {
            pageOk = true;
            checkUpdateOnce();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame()) showFatal("本机页面加载失败：" + e.getDescription());
        }
    }

    private class Chrome extends WebChromeClient {
        /** window.open / target=_blank：借一个临时 WebView 接住目标地址，再交系统浏览器。 */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message msg) {
            if (!isUserGesture) return false;
            final WebView tmp = new WebView(MainActivity.this);
            tmp.getSettings().setJavaScriptEnabled(true);
            final String[] done = new String[1];
            tmp.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    take(v, r.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String u, android.graphics.Bitmap favicon) {
                    take(v, u);
                }

                private void take(WebView v, String u) {
                    if (done[0] != null || u == null || u.startsWith("about:")) return;
                    done[0] = u;
                    if (!isLocal(u)) openExternal(u);
                    v.destroy();
                }
            });
            try {
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
            } catch (Exception e) {
                tmp.destroy();
                return false;
            }
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            fileCb = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(i, "选择备份文件"), REQ_FILES);
            } catch (Exception e) {
                fileCb = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILES && fileCb != null) {
            ValueCallback<Uri[]> cb = fileCb;
            fileCb = null;
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
        }
    }

    /* ---------- 导出落盘 ---------- */

    private class Down implements android.webkit.DownloadListener {
        @Override
        public void onDownloadStart(String u, String ua, String disp, String mime, long len) {
            String name = disp == null ? null : URLUtil.guessFileName(u, disp, mime);
            if (name == null || name.length() == 0) name = "release-export.json";
            if (u.startsWith("blob:")) {
                /* blob: 只活在渲染进程里，DownloadManager 取不到——改用页内 fetch 转 base64 递回来 */
                String js = "(function(){var u=" + JSONObject.quote(u) + ",n=" + JSONObject.quote(name) + ";"
                        + "fetch(u).then(function(r){return r.blob()}).then(function(b){"
                        + "var fr=new FileReader();fr.onloadend=function(){var s=String(fr.result);"
                        + "var i=s.indexOf(',');AndroidShell.saveFile(n,i<0?'':s.slice(i+1));};"
                        + "fr.readAsDataURL(b);}).catch(function(e){AndroidShell.toast('导出失败：'+e);});})()";
                web.evaluateJavascript(js, null);
                return;
            }
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u));
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, SAVE_DIR + "/" + name);
                r.allowScanningByMediaScanner();
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                getSystemService(DownloadManager.class).enqueue(r);
            } catch (Exception e) {
                toast("导出失败：" + e.getMessage());
            }
        }
    }

    private class Shell {
        @JavascriptInterface
        public void saveFile(final String name, final String b64) {
            byte[] data;
            try {
                data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            } catch (Exception e) {
                toast("导出失败：" + e.getMessage());
                return;
            }
            saveBytes(name, data);
        }

        /** 页内直写文本落盘。blob: 只活在渲染进程里，壳子拿页内 fetch 去取时
         *  页面同一拍就 revoke 了（实测必败）——导出改走这条不碰 blob 的通道。 */
        @JavascriptInterface
        public void saveText(final String name, final String text) {
            byte[] data;
            try {
                data = text.getBytes("UTF-8");
            } catch (Exception e) {
                toast("导出失败：" + e.getMessage());
                return;
            }
            saveBytes(name, data);
        }

        /** 这版壳把返回手势接到了浮层上（先关一层，关不动才退出）。页面据此决定
         *  要不要提示「更新一次 App 才管返回手势」；旧壳没这个方法，取到 undefined。 */
        @JavascriptInterface
        public boolean backHandled() {
            return true;
        }

        private void saveBytes(final String rawName, final byte[] data) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        String fname = rawName.replaceAll("[/\\\\:*?\"<>|]", "_");
                        if (Build.VERSION.SDK_INT >= 29) {
                            ContentValues cv = new ContentValues();
                            cv.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                            cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                                    Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR);
                            Uri uri = getContentResolver().insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                            OutputStream os = getContentResolver().openOutputStream(uri);
                            os.write(data);
                            os.close();
                        } else {
                            File dir = new File(Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS), SAVE_DIR);
                            dir.mkdirs();
                            FileOutputStream fos = new FileOutputStream(new File(dir, fname));
                            fos.write(data);
                            fos.close();
                        }
                        toast("已存到 下载/" + SAVE_DIR + "/" + fname);
                    } catch (Exception e) {
                        toast("导出失败：" + e.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void checkUpdate() {
            runUpdateCheck(true);
        }

        @JavascriptInterface
        public void checkContent() {
            runContentCheck(true);
        }

        /** 内容包代号，0 表示一直用 APK 里那份；页面上「安装包」那一行要显示它。 */
        @JavascriptInterface
        public String contentCode() {
            return String.valueOf(getSharedPreferences("shell", MODE_PRIVATE).getInt("web_code", 0));
        }

        @JavascriptInterface
        public String appVersion() {
            return Updater.currentName(MainActivity.this) + '（'
                    + Updater.currentCode(MainActivity.this) + '）';
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    MainActivity.this.toast(msg);
                }
            });
        }
    }

    private void askWritePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_WRITE);
            }
        }
    }

    /* ---------- 检查更新（只认公网那一个发布地址，电脑开不开机无关） ---------- */

    /**
     * 结果两头递：Toast 是怕人已经划走了看不见，页内回调是给「检查更新」那颗按钮收尾
     * —— 按钮卡在「检查中…」比没这个功能更招人烦。
     */
    private void runUpdateCheck(boolean manual) {
        Updater.start(this, manual, new Updater.Ui() {
            public void onResult(final String text, final boolean ok) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(text);
                        web.evaluateJavascript("window.__atUpd&&window.__atUpd("
                                + JSONObject.quote(text) + "," + (ok ? "true" : "false") + ")", null);
                    }
                });
            }
        });
    }

    /** 首屏画完再悄悄查一次：抢在封面前面占带宽不值，而且这时候弹窗人才看得懂。 */
    private void checkUpdateOnce() {
        if (!updateChecked) {
            updateChecked = true;
            web.postDelayed(new Runnable() {
                public void run() {
                    runUpdateCheck(false);
                }
            }, 2500);
        }
        if (!contentChecked) {
            contentChecked = true;
            web.postDelayed(new Runnable() {
                public void run() {
                    runContentCheck(false);
                }
            }, 4000);   // 错开两秒：两条更新各查各的，但别同时抢带宽
        }
    }

    /** 内容热更的结果同样两头递，理由和上面一样。 */
    private void runContentCheck(boolean manual) {
        WebUpdater.run(MainActivity.this, manual, new WebUpdater.Ui() {
            public void onResult(final String text, final boolean ok) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (manual) toast(text);
                        /* 热更成功当场换根刷新：不再等「下次打开生效」（2026-10-10 用户令）。
                           H5 有断点续走，重载后回到存档那一步，不丢进度。 */
                        if (ok && text.contains("已更新到 code")) {
                            LocalServer s2 = srv;
                            if (s2 != null) {
                                s2.setWebRoot(WebUpdater.currentRoot(MainActivity.this));
                                web.loadUrl(s2.pageUrl());
                                return;
                            }
                        }
                        web.evaluateJavascript("window.__atWeb&&window.__atWeb("
                                + JSONObject.quote(text) + "," + (ok ? "true" : "false") + ")", null);
                    }
                });
            }
        });
    }

    /* ---------- 生命周期与按键 ---------- */

    /**
     * 返回键与全面屏返回手势：先问网页层有没有关得上的浮层（自定义色盘这类整屏抽屉），
     * 网页层说没了才把人交回系统。这里不能用 web.goBack()：整份 App 只有一个地址，
     * 历史里那点条目全是上一次 loadUrl 的旧页面，按返回就成了「退回上一步，而不是桌面」。
     */
    @Override
    public void onBackPressed() {
        if (errPanel.getVisibility() == View.VISIBLE) {
            errPanel.setVisibility(View.GONE);
            return;
        }
        final WebView w = web;
        final boolean[] answered = new boolean[]{false};
        w.evaluateJavascript("window.__back?window.__back():'none'", new ValueCallback<String>() {
            public void onReceiveValue(String raw) {
                answered[0] = true;
                if (raw == null || raw.indexOf("closed") < 0) leaveToDesktop(w);
            }
        });
        // 页面卡住时回调不会来：不能让人按了返回什么也不发生，等半秒就走系统那条路
        w.postDelayed(new Runnable() {
            public void run() {
                if (!answered[0]) leaveToDesktop(w);
            }
        }, 500);
    }

    /** 浮层都关着：退出到桌面。回调可能从别的线程来，所以回 UI 线程再核一次壳还在不在。 */
    private void leaveToDesktop(final WebView w) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (w == web) finish();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        LocalServer s = srv;
        if (s != null) s.stop();
        super.onDestroy();
    }
}
