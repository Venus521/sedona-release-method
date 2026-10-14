package com.venus.releaseapp;

/**
 * 内容热更的货架：清单地址 + 文件基地址 + 可空 Basic 头 + 出错时自报家门的 label。
 * 2026-10-12 起有两条货架：CloudBase（老货架，分发版恒用）与坚果云 WebDAV（自用版配了凭据就用它）。
 * 两条货架共用一份清单格式与一套整包校验（WebUpdater），换货架不换规矩。
 * code 空间各自独立，所以一台手机一次只听一条货架 —— pick() 一次定死，不搞双源合并
 * （双源会让「云端 code 189 / 坚果云 code 1」这种比较把指针搅乱，谁新谁旧说不清）。
 */
public class WebSource {

    public final String label;
    public final String manifestUrl;
    public final String base;
    public final String auth;   /* 可空：发布桶不需要认证，WebDAV 需要 */

    public WebSource(String label, String manifestUrl, String base, String auth) {
        this.label = label;
        this.manifestUrl = manifestUrl;
        this.base = base;
        this.auth = auth;
    }

    /** 本次构建听哪条货架：配了坚果云凭据就坚果云，没配退回 CloudBase，行为与从前一致。 */
    public static WebSource pick() {
        String auth = NutstoreAuth.header();
        if (auth != null) {
            return new WebSource("坚果云", NutstoreAuth.manifestUrl(), NutstoreAuth.base(), auth);
        }
        return new WebSource("云端", WebInfo.MANIFEST, WebInfo.BASE, null);
    }
}
