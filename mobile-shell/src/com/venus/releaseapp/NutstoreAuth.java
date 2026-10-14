package com.venus.releaseapp;

/**
 * 坚果云 WebDAV 货架的地址与凭据 —— 2026-10-12 起，自用版的网页内容热更多了一条货架：
 * 电脑上把内容包发进坚果云（mobile-shell/发布坚果云.py），手机开 App 自己来取，
 * 电脑关机、不在同一 WiFi 都照取。CloudBase 那条货架原样保留，作分发版与未配置时的退路。
 *
 * 这份源码里的 USER/PASS 永远是空壳：真凭据由 build-apk.py 构建时从
 * mobile-shell/坚果云凭据.json（git 忽略）生成进构建拷贝，源码与 git 里永远没有。
 * 空壳也有自己的用处：桌面门禁能把 src 原样编过；空壳＝未配置，
 * WebSource.pick() 会退回 CloudBase 货架，行为与从前一致——分发版由此天然不带凭据，
 * 构建闸门另有反向钉死（分发 dex 里出现密码当场死）。
 *
 * 凭据用「应用密码」不是登录密码：坚果云网页版 → 账户信息 → 安全选项 →
 * 第三方应用管理 → 添加应用密码，随时可吊销，泄漏的代价上限就是吊销一次。
 */
public class NutstoreAuth {

    /** 服务地址与内容目录：目录名刻意全 ASCII，绕开各端对中文路径的编码分歧。 */
    public static final String DAV = "https://dav.jianguoyun.com/dav/";
    public static final String DIR = "release-method/hot/";

    /* 构建时替换下面两行；空串＝未配置。 */
    public static final String USER = "";
    public static final String PASS = "";

    public static boolean enabled() {
        return USER.length() > 0 && PASS.length() > 0;
    }

    /**
     * Basic 认证头；未配置回 null，调用方按「没有这条货架」处理。
     * Base64 自己带一份 RFC 4648：java.util.Base64 要 API 26，壳的 minSdk 是 21；
     * 也不引 android.util.Base64 —— 这份类要能在电脑上原样编译、原样跑断言。
     */
    public static String header() {
        if (!enabled()) return null;
        try {
            return "Basic " + b64((USER + ":" + PASS).getBytes("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 内容清单（web.json）与文件目录，形状与 CloudBase 货架完全一致。 */
    public static String manifestUrl() { return DAV + DIR + "web.json"; }
    public static String base() { return DAV + DIR; }

    static String b64(byte[] raw) {
        final String T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder s = new StringBuilder(((raw.length + 2) / 3) * 4);
        for (int i = 0; i < raw.length; i += 3) {
            boolean has1 = i + 1 < raw.length;
            boolean has2 = i + 2 < raw.length;
            int b0 = raw[i] & 0xFF;
            int b1 = has1 ? raw[i + 1] & 0xFF : 0;
            int b2 = has2 ? raw[i + 2] & 0xFF : 0;
            int n = (b0 << 16) | (b1 << 8) | b2;
            s.append(T.charAt((n >> 18) & 63)).append(T.charAt((n >> 12) & 63));
            s.append(has1 ? T.charAt((n >> 6) & 63) : '=');
            s.append(has2 ? T.charAt(n & 63) : '=');
        }
        return s.toString();
    }
}
