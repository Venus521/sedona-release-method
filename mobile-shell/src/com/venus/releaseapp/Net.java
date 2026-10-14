package com.venus.releaseapp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 取云端文件的那点 HTTP 活儿，单独一个类、只用 JDK。
 *
 * 为什么从 Updater 里搬出来：Updater 满手 AlertDialog/Intent/Uri，桌面上根本编不动，
 * 而内容包（WebUpdater）与 APK 包（Updater）必须走**同一套**取文件规矩 ——
 * 不跟跳转、超上限就断、HTTP 码说得清。搬出来才能两边都引，也才能在电脑上原样跑断言。
 */
public class Net {

    private static final int CONNECT_MS = 10000;
    private static final int READ_MS = 20000;

    /** 发布地址是我们自己的桶，这里刻意不跟跳转：一跳转域名就不受白名单管了。 */
    static HttpURLConnection open(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json, application/octet-stream, */*");
        return c;
    }

    public static byte[] readBytes(String spec, long cap) throws Exception { return readBytes(spec, cap, null); }
    public static String readText(String spec, long cap) throws Exception { return readText(spec, cap, null); }

    /** 二进制：不跟跳转、超出 cap 立刻断，绝不"尽力收下"一个半截文件。
     *  auth 可空：发布桶不需要认证；坚果云 WebDAV 这类货架要 Basic 头。 */
    public static byte[] readBytes(String spec, long cap, String auth) throws Exception {
        HttpURLConnection c = open(spec);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        try {
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                throw new Exception("云端把地址跳到了别处（HTTP " + code + "），为安全不跟跳");
            }
            if (code != 200) throw new Exception("云端回了 HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got > cap) throw new Exception("内容超出上限 " + cap + " 字节");
                bo.write(buf, 0, n);
            }
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    public static String readText(String spec, long cap, String auth) throws Exception {
        byte[] raw = readBytes(spec, cap, auth);
        if (raw.length == 0) throw new Exception("云端回来的内容是空的");
        return new String(raw, "UTF-8");
    }

    static String hex(byte[] raw) {
        StringBuilder s = new StringBuilder(raw.length * 2);
        for (int i = 0; i < raw.length; i++) {
            String h = Integer.toHexString(raw[i] & 0xFF);
            if (h.length() < 2) s.append('0');
            s.append(h);
        }
        return s.toString();
    }
}
