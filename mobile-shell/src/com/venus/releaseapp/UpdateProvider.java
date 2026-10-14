package com.venus.releaseapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把下载好的升级包交给系统安装器读。
 *
 * 正规做法是 androidx 的 FileProvider，但这个壳不走 Gradle，classpath 上只有
 * android.jar —— 引 support 库要为了一件小事拖进整个依赖树，所以手搭一个最小的：
 * 只承认 content://本authority/apk/AniTracker-&lt;数字&gt;.apk 这一种形状，
 * 并且强制它真的落在 update 目录里。文件名先过正则再看规范路径，两道的。
 */
public class UpdateProvider extends ContentProvider {

    public static final String AUTH = "com.venus.releaseapp.update";
    private static final int APK = 1;
    private static final UriMatcher MATCH = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCH.addURI(AUTH, "apk/*", APK);
    }

    /** 下载方和读取方共用这一个目录，免得两边各拼一次就拼歪了。 */
    public static File dir(Context c) throws FileNotFoundException {
        File base = c.getExternalFilesDir(null);
        if (base == null) throw new FileNotFoundException("手机没有可用的外部目录");
        File d = new File(base, "update");
        if (!d.isDirectory() && !d.mkdirs()) throw new FileNotFoundException("建不出 update 目录");
        return d;
    }

    public static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTH)
                .appendPath("apk").appendPath(f.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (MATCH.match(uri) != APK) throw new FileNotFoundException("不认识的地址 " + uri);
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("AniTracker-\\d+\\.apk")) {
            throw new FileNotFoundException("文件名不对");
        }
        File root = dir(getContext());
        File f = new File(root, name);
        try {
            // 名字已经卡死了，这条是给以后的改动兜底：谁也别想从这里读出别处的文件
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException("越界");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("路径判不了：" + e);
        }
        if (!f.isFile()) throw new FileNotFoundException("文件不在：" + name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException();
    }
}
