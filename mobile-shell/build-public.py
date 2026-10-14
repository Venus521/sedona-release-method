# -*- coding: utf-8 -*-
"""生成分发版（对外公开用）：网页包 + APK，两样都不带云同步凭据。

为什么要有这一份
----------------
自用版 sync-config.js 里带一把匿名 KEY，谁拿到 APK 或网页包都能往作者的 CloudBase
库里写东西、刷免费额度。对外开放下载之前必须出一份「摘掉凭据」的：

  · 资源包  index.html + vendor/cloudbase.full.js + release-sync.js + sync-config.js
    sync-config.js 是本脚本生成的三行空壳（ENV/KEY 留空），release-sync.js 读到空凭据
    会自动停用同步、面板里显示一句说明 —— 这条降级路本来就写好了。
  · 壳      真包由 build-apk.py --public 走完整 aapt2/d8/签名链产出。本脚本只把上面
    四个文件拼成 dist-public/ShiFangFa-public.assets.zip（资源载荷，**不是 APK**），
    10-12 实测：aapt2 认不出它的格式。过去它借用 .apk 这个文件名，害得对账时把
    「包内页落后一版」读成「包没打」，所以改名。

不碰自用版
----------
本脚本只读 release-method/ 下的真身，所有产物落到 dist-public/。自用版的
dist/、sync-config.js、build-apk.py 一个字节都不动。

用法
----
python mobile-shell/build-public.py            # 只出 dist-public/ 产物，不上传
python mobile-shell/build-public.py --apk      # 顺带出 APK（默认就出，设此旗子是为了显式）
上传用 hosting CLI（脚本会打印确切命令），或交给 发布到云端.py --public 分支。
"""
import io
import json
import os
import re
import shutil
import sys
import time
import zipfile

SHELL = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SHELL)                       # release-method/
SRC = os.path.join(ROOT, 'mobile-shell', 'assets', 'web')
DIST = os.path.join(SHELL, 'dist-public')
WEB_OUT = os.path.join(DIST, 'web')
APK_OUT = os.path.join(DIST, 'ShiFangFa-public.assets.zip')
BUILD_PY = os.path.join(SHELL, 'build-apk.py')


def shelf_version():
    """版本号只有一个真源：build-apk.py 的 PUBLIC 支，这里抄它，不许两份各写各的。

    10-12 踩过：这份里硬写着 19／2.8，build-apk.py 早已涨到 24／3.3，
    照抄旧数写出来的 public.json 就是假标注（标注说 19、包里 aapt2 实读 24）。
    """
    t = read(BUILD_PY)
    c = re.search(r"^VERSION_CODE\s*=\s*'(\d+)'\s+if\s+PUBLIC\s+else", t, re.M)
    n = re.search(r"^VERSION_NAME\s*=\s*'([^']+)'\s+if\s+PUBLIC\s+else", t, re.M)
    if not c or not n:
        die('build-apk.py 里版本号写法变了，抄不过来 —— 去那一份改')
    return c.group(1), n.group(1)

WEB_FILES = [
    'index.html',
    'vendor/cloudbase.full.js',
    'release-sync.js',
    'sync-config.js',
]

# 无凭据的凭据文件。ENV/KEY 留空是刻意的 —— release-sync.js 里
# `if (!(window.RELEASE_SYNC_CFG && ...ENV && ...KEY))` 会走「云同步未配置」那条分支，
# 收获本退回本机存储，导出/导入照常能用。不要在这里填回真 KEY。
PUBLIC_SYNC_CFG = u"""/* 释放法 · 分发版：无云同步凭据
   收获本只存在本机，导出/导入照常可用。要同步就用作者那份带账号的版本。 */
window.RELEASE_SYNC_CFG = { ENV: '', REGION: '', KEY: '' };
"""


def die(msg):
    print('FAIL: ' + msg)
    sys.exit(1)


def read(path, binary=False):
    if binary:
        with open(path, 'rb') as f:
            return f.read()
    return io.open(path, encoding='utf-8').read()


def guard_no_secret(out_dir):
    """闸门：产物里绝不能出现自用版的 KEY / ENV。这一条必须硬拦，不能靠自觉。"""
    real = read(os.path.join(ROOT, 'sync-config.js'))
    env = re.search(r"ENV:\s*'([^']*)'", real)
    key = re.search(r"KEY:\s*'([^']*)'", real)
    secrets = [s for s in (env.group(1) if env else '', key.group(1) if key else '') if s]
    if not secrets:
        print('  ⚠ 自用版 sync-config.js 里没读到凭据，跳过密钥扫描')
        return
    bad = []
    for rel in WEB_FILES:
        p = os.path.join(out_dir, rel)
        if not os.path.exists(p):
            continue
        t = read(p)
        for s in secrets:
            if s and s in t:
                bad.append('%s 里出现了自用凭据' % rel)
    for rel in ['AndroidManifest.xml', 'build-apk.py']:
        pass
    if bad:
        die('分发产物混进了自用凭据：' + '；'.join(sorted(set(bad))) + '\n  这是硬拦截，先修掉再发。')
    print('  密钥扫描 OK：分发产物里没有自用 ENV/KEY')


def build_web():
    if os.path.isdir(WEB_OUT):
        os.rename(WEB_OUT, WEB_OUT + '.stale-%d' % int(time.time()))
    os.makedirs(WEB_OUT)
    for rel in WEB_FILES:
        s = os.path.join(SRC, rel)
        if not os.path.exists(s):
            die('缺源文件：%s' % s)
        if rel == 'sync-config.js':
            d = os.path.join(WEB_OUT, rel)
            os.makedirs(os.path.dirname(d), exist_ok=True)
            with io.open(d, 'w', encoding='utf-8', newline='\n') as f:
                f.write(PUBLIC_SYNC_CFG)
            continue
        d = os.path.join(WEB_OUT, rel)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        shutil.copy2(s, d)
    # 名单外的文件不许留在产物里（比如哪次忘了清的旧文件）
    keep = set(WEB_FILES)
    extra = []
    for base, _, fs in os.walk(WEB_OUT):
        for f in fs:
            rel = os.path.relpath(os.path.join(base, f), WEB_OUT).replace('\\', '/')
            if rel not in keep:
                extra.append(rel)
    if extra:
        for rel in extra:
            os.remove(os.path.join(WEB_OUT, rel))
        print('  清掉名单外文件 %d 个：%s' % (len(extra), '、'.join(extra)))
    guard_no_secret(WEB_OUT)
    return {rel: os.path.getsize(os.path.join(WEB_OUT, rel)) for rel in WEB_FILES}


def build_assets_zip():
    """把 web/ 这批（无凭据）拼成资源载荷。真壳在 build-apk.py --public 那一步打。"""
    if os.path.exists(APK_OUT):
        os.rename(APK_OUT, APK_OUT + '.stale-%d' % int(time.time()))
    with zipfile.ZipFile(APK_OUT, 'w', zipfile.ZIP_DEFLATED) as z:
        for rel in WEB_FILES:
            z.write(os.path.join(WEB_OUT, rel), 'assets/web/' + rel)
    # 对账：载荷里那份 index.html 必须就是刚出的网页包那份
    with zipfile.ZipFile(APK_OUT) as z:
        have = z.read('assets/web/index.html')
    want = read(os.path.join(WEB_OUT, 'index.html'), binary=True)
    if have != want:
        die('载荷内 index.html 与分发网页包字节不一致')
    with zipfile.ZipFile(APK_OUT) as z:
        cfg = z.read('assets/web/sync-config.js').decode('utf-8')
    if re.search(r"KEY:\s*'[^']+'", cfg):
        die('载荷里的 sync-config.js 带着非空 KEY，分发包不能有凭据')
    print('  载荷对账 OK：内嵌 index.html 与分发网页包一致 · 内嵌 sync-config 无凭据')
    return os.path.getsize(APK_OUT)


def main():
    code, name = shelf_version()
    print('[1/2] 出分发网页包（不带云同步凭据）')
    sizes = build_web()
    total = 0
    for rel in WEB_FILES:
        print('  %-26s %8d B' % (rel, sizes[rel]))
        total += sizes[rel]
    print('  合计 %d B' % total)

    print('[2/2] 出资源载荷（真壳交给 build-apk.py --public）')
    n = build_assets_zip()
    print('  %s  %d B' % (os.path.basename(APK_OUT), n))

    with io.open(os.path.join(DIST, 'public.json'), 'w', encoding='utf-8') as f:
        f.write(json.dumps({'versionCode': code, 'versionName': name,
                            'assetsZip': os.path.basename(APK_OUT), 'assetsSize': n,
                            'files': sizes}, ensure_ascii=False, indent=2))
    print('\nOK 分发产物在 %s（自用版的 dist/ 一个字节没动）' % DIST)
    print('  出壳：python mobile-shell/build-apk.py --public')
    print('  上传网页包：tcb hosting deploy -e cloud1-d7gsn5t0w6407b963 %s <远端路径>'
          % WEB_OUT.replace('\\', '/'))
    print('  下一版号 v%s（versionCode %s，抄自 build-apk.py）· 对外发之前先确认产物里没有你的凭据'
          % (name, code))


if __name__ == '__main__':
    main()