package com.venus.releaseapp;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 只监听 127.0.0.1 的迷你静态服务器：把 APK 里 assets/web 那份释放法喂给 WebView。
 *
 * 为什么绕这一圈而不用 file:///android_asset 或 WebViewAssetLoader：
 * CloudBase 网关的跨域白名单只放行 localhost/127.0.0.1 这类源（已实测任意端口都认），
 * https://appassets.androidplatform.net 与 file:// 都会被 403 拦死——登录直接废。
 * 走 127.0.0.1 就变成和电脑上完全一样的环境，登录直连云端，不需要电脑。
 *
 * 顺带：上游（家里电脑）在线时代理取最新版，离线/关机则回落到内置副本，
 * 于是「电脑在=自动最新」「电脑不在=照样能用」两件事同时成立。
 */
public class LocalServer {

    private static final byte[] EMPTY = new byte[0];

    /** 首选端口：与电脑上那套一致；被占了再往后退，白名单不挑端口。 */
    private static final int[] PORTS = {8089, 8090, 8091, 8092, 8093, 8094, 8095};
    static final int UPSTREAM_PORT = 8089;

    private final AssetManager assets;
    private final ServerSocket ss;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final int port;
    /** 热更层目录（可空）：里面有同一套网页文件，优先于 APK 内置那份。 */
    private final AtomicReference<File> overlay = new AtomicReference<File>();

    /** 家里电脑的基地址，null 表示这次加载不试上游。 */
    private volatile String upstream;
    /** 上游连不上一次就整页放弃，免得每个资源都白等 900ms。 */
    private volatile boolean upstreamOn;
    private volatile boolean stopped;

    private LocalServer(Context ctx, int port, String up) throws IOException {
        this.assets = ctx.getAssets();
        this.ss = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
        this.port = this.ss.getLocalPort();
        this.upstream = up;
        this.upstreamOn = up != null && up.length() > 0;
    }

    public static LocalServer start(Context ctx, String up) throws IOException {
        return start(ctx, up, null);
    }

    public static LocalServer start(Context ctx, String up, File webRoot) throws IOException {
        IOException last = null;
        LocalServer s = null;
        for (int i = 0; i < PORTS.length; i++) {
            try {
                s = new LocalServer(ctx, PORTS[i], up);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (s == null) {
            try {
                s = new LocalServer(ctx, 0, up);
            } catch (IOException e) {
                throw (last != null ? last : e);
            }
        }
        s.setWebRoot(webRoot);
        s.serve();
        return s;
    }

    /**
     * 绑定 ≠ 在服务：accept 循环必须自己起线程跑。
     * 漏掉这一步的表现是「socket 建好了但没人 accept」→ WebView 连接被拒 → 整页白屏。
     */
    private void serve() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                serveLoop();
            }
        }, "at-server");
        t.setDaemon(true);
        t.start();
    }

    public String pageUrl() {
        return "http://127.0.0.1:" + port + "/index.html";
    }

    public void setUpstream(String base) {
        upstream = base;
        upstreamOn = base != null && base.length() > 0;
    }

    /** 换上（或摘掉）热更层。只认带 index.html 的目录，半截内容一律当没有。 */
    public void setWebRoot(File root) {
        if (root != null && !new File(root, "index.html").isFile()) root = null;
        overlay.set(root);
    }

    /** 每次真正加载页面时重新给上游一次机会。 */
    public void retryUpstream() {
        upstreamOn = upstream != null && upstream.length() > 0;
    }

    public void stop() {
        stopped = true;
        try {
            ss.close();
        } catch (IOException e) {
            /* 正在 accept 的那个调用会随之退出 */
        }
        pool.shutdownNow();
    }

    private void serveLoop() {
        while (!stopped) {
            final Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                return;
            }
            try {
                pool.execute(new Runnable() {
                    public void run() {
                        handle(s);
                    }
                });
            } catch (Exception e) {
                close(s);
            }
        }
    }

    /* ---------- 单条请求 ---------- */

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            String req = readLine(in);
            if (req == null) {
                close(s);
                return;
            }
            String hl;
            while ((hl = readLine(in)) != null && hl.length() > 0) {
                /* 请求头一概不需要，读完让内核别卡在管道上 */
            }
            String[] parts = req.split(" ");
            boolean head = parts.length >= 2 && "HEAD".equals(parts[0]);
            String path = parsePath(req);
            if (path == null) {
                write(s, 400, "text/plain; charset=utf-8", head ? EMPTY : "bad request".getBytes("UTF-8"));
                close(s);
                return;
            }
            byte[] body = loadPage(path);
            if (body == null) {
                write(s, 404, "text/plain; charset=utf-8", head ? EMPTY : "not found".getBytes("UTF-8"));
            } else {
                write(s, 200, typeOf(path), head ? EMPTY : body);
            }
        } catch (Exception e) {
            /* 客户端中途断开是常态 */
        } finally {
            close(s);
        }
    }

    /** "GET /a/b.js?v=1 HTTP/1.1" → "/a/b.js"；越界或畸形返回 null。 */
    private static String parsePath(String req) {
        String[] parts = req.split(" ");
        if (parts.length < 2 || !("GET".equals(parts[0]) || "HEAD".equals(parts[0]))) return null;
        String p = parts[1];
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        p = decode(p);
        if (!p.startsWith("/") || p.indexOf('\\') >= 0) return null;
        if (p.contains("..")) return null;
        if (p.equals("/")) p = "/index.html";
        return p;
    }

    private static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    out.write((int) Long.parseLong(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    /* 不是转义，原样落 */
                }
            }
            if (c == '+') c = ' ';
            out.write(c & 0xFF);
        }
        return new String(out.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    /**
     * 页面来源三层：上游（家里电脑在线取最新）→ 热更层（手机自己从云端下的内容）→ APK 内置。
     * 三层任何一层缺文件都自动往下退，所以半套内容不可能把页面卡死。
     */
    private byte[] loadPage(String path) {
        byte[] body = upstreamOn ? viaUpstream(path) : null;
        if (body == null) body = fromOverlay(path);
        if (body == null) body = fromAssets(path);
        return body;
    }

    private byte[] fromOverlay(String path) {
        File root = overlay.get();
        if (root == null) return null;
        File f = new File(root, path.substring(1));
        if (!f.isFile()) return null;
        try {
            return readAll(new java.io.FileInputStream(f));
        } catch (IOException e) {
            return null;   // 读不到就退回内置，绝不让页面断在这儿
        }
    }

    private byte[] fromAssets(String path) {
        try {
            InputStream is = assets.open("web" + path);
            return readAll(is);
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] viaUpstream(String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(upstream + path).openConnection();
            c.setConnectTimeout(900);
            c.setReadTimeout(3000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "AniTrackerShell/1.0");
            int code = c.getResponseCode();
            if (code != 200) return null;           // 上游没有这文件，交给内置副本
            InputStream is = c.getInputStream();
            byte[] b = readAll(is);
            close(is);
            return b;
        } catch (IOException e) {
            upstreamOn = false;                     // 电脑不在，本次加载全走内置
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------- 出口 ---------- */

    private static void write(Socket s, int code, String type, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK\r\n" : " Not Found\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        OutputStream os = s.getOutputStream();
        os.write(sb.toString().getBytes("US-ASCII"));
        os.write(body);
        os.flush();
    }

    private static String typeOf(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(is.available(), 8192));
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            close(is);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            /* 忽略 */
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        if (b < 0 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "US-ASCII");
    }

    /* ---------- 同网段自动发现家里电脑 ---------- */

    /**
     * 路由器重新分配 IP 后不必重新打包、也不必手输地址：在**保存过的那段 /24** 里
     * 找 8089 上应答的释放法服务器。只在同段扫，跨网段（公司/咖啡馆 WiFi）一律不动手。
     */
    public static String discover(String savedBase) {
        if (savedBase == null || savedBase.length() == 0) return null;
        String want = prefixOf(savedBase);
        String mine = localPrefix();
        if (want == null || mine == null || !want.equals(mine)) return null;
        final AtomicReference<String> hit = new AtomicReference<String>();
        ExecutorService ex = Executors.newFixedThreadPool(24);
        for (int i = 1; i <= 254; i++) {
            final String host = mine + i;
            ex.execute(new Runnable() {
                public void run() {
                    if (hit.get() != null) return;
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL("http://" + host + ":" + UPSTREAM_PORT
                                + "/tracker-version.json").openConnection();
                        c.setConnectTimeout(260);
                        c.setReadTimeout(400);
                        c.setUseCaches(false);
                        if (c.getResponseCode() == 200) hit.compareAndSet(null, c.getURL().getProtocol()
                                + "://" + host + ":" + UPSTREAM_PORT);
                    } catch (IOException e) {
                        /* 这台不是 */
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            });
        }
        ex.shutdown();
        try {
            ex.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.shutdownNow();
        return hit.get();
    }

    private static String prefixOf(String base) {
        try {
            String host = new URL(base).getHost();
            return host == null ? null : host.substring(0, host.lastIndexOf('.') + 1);
        } catch (Exception e) {
            return null;
        }
    }

    private static String localPrefix() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    byte[] b = a.getAddress();
                    if (b.length != 4 || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    return (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + ".";
                }
            }
        } catch (Exception e) {
            /* 拿不到就不扫 */
        }
        return null;
    }
}
