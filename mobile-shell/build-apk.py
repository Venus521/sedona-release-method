"""构建释放法手机壳 APK —— 由追迹 build-apk.py 适配（链路同源，勿在追迹工程里改本份）。
用法：python mobile-shell/build-apk.py              自用版（带云同步凭据）→ dist/ShiFangFa.apk
      python mobile-shell/build-apk.py --public     分发版（无凭据）      → dist-public/…public.apk

--public 走的是同一条 aapt2/javac/d8/zipalign/apksigner 链，只是网页资源改从
dist-public/web/ 取（build-public.py 产的那批，sync-config.js 是空凭据壳）。
不做这条分支的话，网页版能发、APK 里却还是自用那份带 KEY 的 —— 那等于把钥匙一起寄出去。
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = r'D:\dev\android-build'
JDK = os.path.join(ROOT, 'jdk-17.0.20.1+1')
BT = os.path.join(ROOT, r'sdk\build-tools\34.0.0')
ANDROID_JAR = os.path.join(ROOT, r'sdk\platforms\android-34\android.jar')

SRC = r'D:\项目\04_创作写作\release-method\mobile-shell'
WEB = os.path.dirname(SRC)            # 项目根：这份释放法要整个打进 APK
BUILD = r'D:\dev\rel-shell-build'      # 纯 ASCII

# --public 分支：网页资源从 dist-public/web 取、产物另落 dist-public，版本号跟着分发版走
PUBLIC = '--public' in sys.argv[1:]
DIST = os.path.join(SRC, 'dist-public' if PUBLIC else 'dist')

MIN_API = '21'
# ⚠️ 改了网页内容就必须把 VERSION_CODE 加一。
# WebUpdater.currentRoot() 只在 APK versionCode 变化时才作废设备上那份热更网页；
# 版本号不动的话，用户装上新版 APK 仍会加载上次热更的旧页面——表现为「装了新包但界面没变」。
VERSION_CODE = '25' if PUBLIC else '51'
VERSION_NAME = '3.4' if PUBLIC else '5.10'
APK_NAME = 'ShiFangFa-public.apk' if PUBLIC else 'ShiFangFa.apk'

# 打进 assets/web 的文件：index.html 引用的全部同源资源，缺一个就白屏/缺库
WEB_FILES = [
    'index.html',
    'vendor/cloudbase.full.js',   # 云端 SDK：收获本跨设备同步用
    'release-sync.js',            # 同步引擎（同一 CloudBase 环境，仅同步收获本）
    'sync-config.js',             # 云同步凭据（ENV/REGION/KEY）：源码已不含字面量，轮换只改这份
]


def _md5(p):
    h = hashlib.md5()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()


def sync_assets():
    """把项目根那份追迹镜像到 mobile-shell/assets/web（多余的删掉，别留旧版本）。

    2026-10-04：原先靠 mtime 判断要不要重拷（目标不比源新就不拷）。这在 checkout /
    拷这块盘 / 时钟回拨之后会误判成「已同步」，两版就此静默漂移。改成比对内容哈希，
    并在真同步时把文件名打出来 —— 构建了但没换行是最难查的一种错。

    --public 例外：分发版写自己那份 assets-pub/web，不碰 assets/web。
    assets/web 是自用版的构建输入，一旦被分发版的空凭据 sync-config.js 覆盖，
    下次构建自用版就会静默变成单机版（真源没问题，产物却错了）。"""
    dst = os.path.join(SRC, 'assets-pub' if PUBLIC else 'assets', 'web')
    keep = set(WEB_FILES)
    if os.path.isdir(dst):
        for base, _, fs in os.walk(dst):
            for f in fs:
                rel = os.path.relpath(os.path.join(base, f), dst).replace('\\', '/')
                if rel not in keep:
                    os.remove(os.path.join(base, f))
    total = 0
    drift = []
    # --public：从 dist-public/web 取（build-public.py 产的无凭据那批）；否则从项目根真身取
    web_src = os.path.join(SRC, 'dist-public', 'web') if PUBLIC else WEB
    if PUBLIC and not os.path.isdir(web_src):
        die('分发网页包不在，先跑：python mobile-shell/build-public.py')
    for rel in WEB_FILES:
        s = os.path.join(web_src, rel.replace('/', os.sep))
        d = os.path.join(dst, rel.replace('/', os.sep))
        if not os.path.exists(s):
            die('缺 Web 资源：' + s)
        if not os.path.isdir(os.path.dirname(d)):
            os.makedirs(os.path.dirname(d))
        need = True
        if os.path.exists(d):
            need = _md5(d) != _md5(s)
        if need:
            shutil.copy2(s, d)
            drift.append(rel)
        total += os.path.getsize(d)
    print('  内置网页 %.2f MB（%d 个文件）' % (total / 1048576.0, len(WEB_FILES)))
    if drift:
        print('  本轮同步：' + '、'.join(drift) + ' ← 源码')
    else:
        print('  内置网页与源码内容一致，无需重拷')

# d8/apksigner 是批处理脚本，进去还要再调 java —— 必须把 JAVA_HOME 递下去。
# System32 也要显式补：d8.bat 头一行就是 chcp，PATH 里没有它会直接
# 「'chcp' 不是内部或外部命令」退出 1。venv 与托管 python 继承到的 PATH
# 常常已经把系统目录弄丢了，所以不能指望外面 export（那样对 venv 无效）。
ENV = dict(os.environ)
ENV['JAVA_HOME'] = JDK
_SYS32 = os.path.join(os.environ.get('SystemRoot', r'C:\Windows'), 'System32')
_paths = [os.path.join(JDK, 'bin'), _SYS32, os.environ.get('SystemRoot', '')]
_paths += [p for p in ENV.get('PATH', '').split(os.pathsep) if p]
ENV['PATH'] = os.pathsep.join([p for p in _paths if p])


def die(msg):
    print('FAIL: ' + msg)
    sys.exit(1)


def sh(cmd, name):
    # d8/apksigner 是 .bat，CreateProcess 不能直接起批处理，必须过 cmd /c
    if cmd[0].lower().endswith('.bat'):
        cmd = ['cmd', '/c'] + cmd
    # d8/apksigner 经 cmd 回出来的中文是 GBK，text=True 默认按 UTF-8 硬解会抛
    # UnicodeDecodeError —— 那个 reader 线程一炸 p.stdout 就成了 None，退出码跟着被
    # 误判成失败。errors='replace' 保证输出能读出来，真失败也能看到原因。
    p = subprocess.run(cmd, capture_output=True, text=True, env=ENV,
                       encoding='utf-8', errors='replace')
    out = ((p.stdout or '') + (p.stderr or '')).strip()
    if p.returncode != 0:
        print(out[-3000:])
        die(name + ' 退出码 ' + str(p.returncode))
    tail = [l for l in out.splitlines() if l.strip()][-3:] if out else []
    print('  %s: %s' % (name, ' | '.join(tail) if tail else 'ok'))


def main():
    if not os.path.exists(ANDROID_JAR):
        die('缺 android.jar，先跑 D:\\dev\\android-build\\install-sdk.py')

    print('[1/8] 同步网页资源进 assets/web（APK 自带一份，电脑关机也能用）')
    sync_assets()

    print('[2/8] 复制源码到 ASCII 构建目录 ' + BUILD)
    if os.path.exists(BUILD):
        # 本机有删除保护：整目录 rmtree 会被拦下并终止进程（连 try/except 都接不住）。
        # 改成改名挪到同盘中转，下次覆盖即可——绝不调用删除 API。
        try:
            os.rename(BUILD, BUILD + '.bak-' + str(int(time.time())))
        except Exception:
            pass
    os.makedirs(BUILD)
    # 落到构建目录时一律叫 assets —— aapt2 的 -A 参数按这个名找，写成 assets-pub 会报
    # 「系统找不到指定的文件」。源侧才是分开的（assets-pub/web vs assets/web）。
    for src_item, dst_item in (('src', 'src'), ('res', 'res'),
                               ('assets-pub' if PUBLIC else 'assets', 'assets'),
                               ('AndroidManifest.xml', 'AndroidManifest.xml')):
        s = os.path.join(SRC, src_item)
        d = os.path.join(BUILD, dst_item)
        if os.path.isdir(s):
            shutil.copytree(s, d)
        else:
            shutil.copy2(s, d)

    if PUBLIC:
        # 分发版必须换掉热更货架前缀。UpdateInfo/WEBInfo 里写死了 /apprel/，
        # 那是自用版的货架 —— 不换的话，别人装的 APK 第一次联网就会去下你那份
        # 带 KEY 的 sync-config.js，前面摘凭据的功夫当场白费。
        # 只改 BUILD 这份拷贝里的 .java，源码不动。
        n = 0
        for base, _, fs in os.walk(os.path.join(BUILD, 'src')):
            for f in fs:
                if not f.endswith('.java'):
                    continue
                p = os.path.join(base, f)
                with open(p, encoding='utf-8') as rd:
                    t = rd.read()
                if '/apprel/' in t:
                    with open(p, 'w', encoding='utf-8', newline='') as w:
                        w.write(t.replace('/apprel/', '/apppub/'))
                    n += 1
        if n == 0:
            die('--public 没找到任何 /apprel/ 前缀 —— 壳的货架路径变了？分发版不能复用自用货架')
        print('  分发版货架前缀 /apprel/ → /apppub/（改了 %d 个 .java，只改拷贝不改源码）' % n)

    # ── 坚果云 WebDAV 凭据：真凭据只住在 mobile-shell/坚果云凭据.json（git 忽略，不入源码）。
    #    构建拷贝里的 NutstoreAuth.java 按 flavor 生成：自用版有凭据就注入，没有就空壳；
    #    分发版永远空壳 —— 下面 dex 闸门两头钉死，谁越线构建当场死。
    NUTSTORE = {'user': '', 'pass': '', 'injected': False}
    _ns_java = os.path.join(BUILD, 'src', 'com', 'venus', 'releaseapp', 'NutstoreAuth.java')
    _cred_path = os.path.join(SRC, '坚果云凭据.json')
    _cred = None
    if os.path.exists(_cred_path):
        try:
            _cred = json.load(open(_cred_path, encoding='utf-8'))
        except Exception as e:
            die('坚果云凭据.json 读不出来：%s' % e)
        NUTSTORE['user'] = str(_cred.get('user', '')).strip()
        NUTSTORE['pass'] = str(_cred.get('pass', '')).strip()
        if not (NUTSTORE['user'] and NUTSTORE['pass']):
            die('坚果云凭据.json 里 user/pass 有空值 —— 要么填全，要么删掉这份文件走云端货架')
    if not PUBLIC and NUTSTORE['pass']:
        _t = io.open(_ns_java, encoding='utf-8').read()
        _t2 = _t.replace('public static final String USER = "";',
                         'public static final String USER = %s;' % json.dumps(NUTSTORE['user'], ensure_ascii=True))
        _t2 = _t2.replace('public static final String PASS = "";',
                          'public static final String PASS = %s;' % json.dumps(NUTSTORE['pass'], ensure_ascii=True))
        if _t2 == _t:
            die('NutstoreAuth.java 里找不到空壳常量 —— 源码模板变了，凭据没注进去')
        io.open(_ns_java, 'w', encoding='utf-8', newline='').write(_t2)
        NUTSTORE['injected'] = True
        print('  坚果云货架：注入凭据（user=%s），自用版热更走 WebDAV' % NUTSTORE['user'])
    else:
        print('  坚果云货架：未注入（%s）' % ('分发版恒空壳' if PUBLIC else '凭据文件缺席，自用版热更退回云端货架'))
    out = os.path.join(BUILD, 'out')
    os.makedirs(out)

    aapt2 = os.path.join(BT, 'aapt2.exe')
    d8 = os.path.join(BT, 'd8.bat')
    zipalign = os.path.join(BT, 'zipalign.exe')
    apksigner = os.path.join(BT, 'apksigner.bat')
    javac = os.path.join(JDK, 'bin', 'javac.exe')
    keytool = os.path.join(JDK, 'bin', 'keytool.exe')

    print('[3/8] aapt2 compile res/')
    res_zip = os.path.join(out, 'res.zip')
    sh([aapt2, 'compile', '--dir', os.path.join(BUILD, 'res'), '-o', res_zip], 'aapt2 compile')

    print('[4/8] aapt2 link（生成 R.java + 未签名包骨架）')
    link_apk = os.path.join(out, 'link.apk')
    gen = os.path.join(out, 'gen')
    sh([aapt2, 'link', '-o', link_apk, '-I', ANDROID_JAR,
        '--manifest', os.path.join(BUILD, 'AndroidManifest.xml'),
        '-A', os.path.join(BUILD, 'assets'),
        '--min-sdk-version', MIN_API, '--target-sdk-version', '34',
        '--version-code', VERSION_CODE, '--version-name', VERSION_NAME,
        '--java', gen, res_zip], 'aapt2 link')

    print('[5/8] javac 编译 MainActivity + LocalServer + R')
    classes = os.path.join(out, 'classes')
    os.makedirs(classes)
    java_files = []
    for base, _, fs in os.walk(os.path.join(BUILD, 'src')):
        java_files += [os.path.join(base, f) for f in fs if f.endswith('.java')]
    for base, _, fs in os.walk(gen):
        java_files += [os.path.join(base, f) for f in fs if f.endswith('.java')]
    if not java_files:
        die('没找到 .java')
    sh([javac, '-source', '1.8', '-target', '1.8', '-encoding', 'UTF-8',
        '-bootclasspath', ANDROID_JAR, '-classpath', ANDROID_JAR,
        '-d', classes] + java_files, 'javac')

    print('[6/8] d8 转 dex')
    dexdir = os.path.join(out, 'dex')
    os.makedirs(dexdir)
    class_files = []
    for base, _, fs in os.walk(classes):
        class_files += [os.path.join(base, f) for f in fs if f.endswith('.class')]
    sh([d8, '--min-api', MIN_API, '--lib', ANDROID_JAR, '--output', dexdir] + class_files, 'd8')
    dex = os.path.join(dexdir, 'classes.dex')
    if not os.path.exists(dex):
        die('d8 没产出 classes.dex')

    print('[7/8] 注入 dex → zipalign → 签名')
    unsigned = os.path.join(out, 'unsigned.apk')
    with zipfile.ZipFile(link_apk, 'r') as zin, \
            zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as zout:
        for it in zin.infolist():
            zout.writestr(it, zin.read(it.filename))
        zout.write(dex, 'classes.dex')
    aligned = os.path.join(out, 'aligned.apk')
    sh([zipalign, '-f', '4', unsigned, aligned], 'zipalign')

    # keystore 必须住在项目里：BUILD 每次构建整体清空，密钥跟着丢的话
    # 下次打包就换签名，手机装新版会被系统判「签名冲突」直接拒装。
    ksdir = os.path.join(SRC, 'keystore')
    if not os.path.exists(ksdir):
        os.makedirs(ksdir)
    ks = os.path.join(ksdir, 'debug.keystore')
    if not os.path.exists(ks):
        sh([keytool, '-genkeypair', '-v', '-keystore', ks, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'shifangfa', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000',
            '-dname', 'CN=ShiFangFa,OU=Home,O=Home,L=Home,ST=Home,C=CN'], 'keytool')
    if not os.path.exists(DIST):
        os.makedirs(DIST)
    apk = os.path.join(DIST, APK_NAME)
    sh([apksigner, 'sign', '--ks', ks, '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--out', apk, aligned], 'apksigner sign')

    print('[8/8] 校验产物')
    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.startswith('assets/web/')]
        if len(names) != len(WEB_FILES):
            die('assets 数量不对：%d / %d' % (len(names), len(WEB_FILES)))
        print('  内置网页资源 %d 个，合计 %.2f MB' % (
            len(names), sum(z.getinfo(n).file_size for n in names) / 1048576.0))
        # 分发版绝不能带凭据；自用版绝不能没凭据（否则等于默默发了单机版出去）。
        # 两种错法都是「看着构建成功、装上才发现」，只能在这里钉住。
        _cfg = z.read('assets/web/sync-config.js').decode('utf-8', 'replace')
        _has = bool(re.search(r"KEY:\s*'[^']+'", _cfg))
        if PUBLIC and _has:
            die('分发版包里居然有云同步 KEY —— 停下来查 sync-config.js 来源')
        if (not PUBLIC) and not _has:
            die('自用版包里没有 KEY —— assets/web 被分发版的空凭据覆盖了？')
        print('  凭据闸门 OK：%s' % ('分发版（无凭据）' if PUBLIC else '自用版（带凭据）'))
        # 坚果云凭据双向闸门：分发版 dex 里出现应用密码＝把私人货架的钥匙寄了出去；
        # 自用版声称注入了凭据、dex 里却找不到＝生成步骤没生效，装上去只会「更新失败」。
        _dexbytes = z.read('classes.dex')
        _nspass = NUTSTORE['pass'].encode('utf-8') if NUTSTORE['pass'] else b''
        if PUBLIC and _nspass and _nspass in _dexbytes:
            die('分发版 dex 里出现了坚果云应用密码 —— 构建拷贝没换成空壳，停下来查')
        if (not PUBLIC) and NUTSTORE['injected'] and _nspass not in _dexbytes:
            die('自用版声称注入坚果云凭据，dex 里却没有 —— 生成步骤没生效')
        if PUBLIC and _nspass:
            print('  坚果云闸门 OK：分发版（凭据文件在，已确认没带进包）')
        elif NUTSTORE['injected']:
            print('  坚果云闸门 OK：自用版（坚果云热更启用）')
        else:
            print('  坚果云闸门 OK：%s' % ('分发版恒空壳' if PUBLIC else '自用版未配置坚果云（热更走云端货架）'))
    p = subprocess.run(['cmd', '/c', apksigner, 'verify', '--print-certs', apk],
                       capture_output=True, text=True, env=ENV)
    print(((p.stdout or '') + (p.stderr or '')).strip()[:600])
    if p.returncode != 0:
        die('apksigner verify 失败')
    b = subprocess.run([aapt2, 'dump', 'badging', apk], capture_output=True, text=True)
    pkg = ''
    for line in (b.stdout or '').splitlines():
        if line.startswith('package:'):
            pkg = line
        if line.startswith(('package:', 'application-label:', 'uses-permission:',
                            'launchable-activity:', 'sdkVersion:', 'targetSdkVersion:')):
            print('  ' + line)
    if ("versionCode='%s'" % VERSION_CODE) not in pkg:
        die('装上去没更新：包里实际版本不是 %s（%s）' % (VERSION_CODE, pkg.strip()))
    # 更新链路最容易「代码写了但没进包」：manifest 少了 provider 声明，下载好的包递不给安装器；
    # dex 少了类，点「检查更新」只会静默没反应。版本号那次已经踩过，这两条一起钉住。
    x = subprocess.run([aapt2, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk],
                       capture_output=True, text=True)
    if 'UpdateProvider' not in (x.stdout or ''):
        die('APK 的 manifest 里没有 UpdateProvider，更新包递不进系统安装器')
    with zipfile.ZipFile(apk) as z:
        dex = z.read('classes.dex')
    CLASSES = ('MainActivity', 'LocalServer', 'UpdateInfo', 'UpdateProvider', 'Updater',
               'WebInfo', 'WebUpdater', 'Net', 'NutstoreAuth', 'WebSource')
    for cls in CLASSES:
        if cls.encode() not in dex:
            die('classes.dex 里没有 ' + cls + '，这份包的更新功能是空的')
    print('  更新链路到位：provider 已声明，%d 个类都在 dex 里' % len(CLASSES))
    # 启动方式里的「手机版 APK」入口要显示货架上这份是什么版本，别让人盲装
    with open(apk + '.meta', 'w', encoding='utf-8') as f:
        f.write(u'v%s（versionCode %s）' % (VERSION_NAME, VERSION_CODE))
    print('\nOK 产物: %s (%.1f MB)' % (apk, os.path.getsize(apk) / 1048576.0))


if __name__ == '__main__':
    main()
