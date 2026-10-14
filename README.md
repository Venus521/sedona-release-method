# 释放法 · 自走引导（Sedona Release Method）

一个人用的情绪释放引导页。单文件离线网页＋安卓安装包，零依赖、不用注册登录，写下的东西只留在你自己的设备上。

它做的事很简单：把圣多纳释放法的一套自问自答摆在屏幕上，一步一步问你，你照着答就行。它不解读你的答案，也不给建议，只负责把问题一步步摆好。

## 下载

到 [Releases](https://github.com/Venus521/sedona-release-method/releases) 下载：

- **网页版**：Releases 里最新的 zip，解压后双击 `index.html` 就能走。不想下压缩包的话，直接把仓库根目录的 `index.html` 存下来也一样。任何现代浏览器都行，手机浏览器也可以。
- **安卓版**：Releases 里的 `ShiFangFa-public.apk`，装上即用。安装时系统会提示「未知来源应用」，允许一次就好。

两个版本内容相同，都不需要账号。

## 怎么用

打开之后底部有四个页签：

- **引导** —— 主流程。开头写一句这一次的目标，然后一问一答往下走，基本都是点选项。中途关掉页面不要紧，回来接着走。起手屏底下收着一段可折叠的「六步骤」，想先看原理再展开，不看也一点不碍事。
- **八节课** —— 同一套练习拆成八节课，一屏一问，适合每天来一课。
- **工作本** —— 按「方面」分卡片的随手写。
- **收获本** —— 走完的记录都收在这里，底行一颗钮直接导出成 TXT。

顶栏「外观」里换配色（纸／雾／墨／跟随系统）和语言（中文 / English）。切到英文后引导问句整页跟着翻，六步骤等官版原文保持中英对照。

## 记录存在哪

只存在你这台设备的浏览器本地存储里，不在任何服务器上。两件事提前说清：

- 清浏览器的站点数据、或卸载 App 时连数据一起清，记录就没了——所以收获本那个 TXT 导出值得定期按一下；
- 导出的 TXT 是给人看、给人存的，没有「导回来」这一步。两台设备之间搬记录目前没有自动通道，这是「数据只在本机、不养账号」这个取舍的另一面。

## 联网这件事

- **网页版**：就是一个本地文件，打开后不发出任何网络请求。
- **安卓版**：页面和数据同样全在本机。联网只做一件事：向作者的静态托管地址检查有没有新版（启动时自动查一次，顶栏「检查更新」钮也是它）。你的记录不会上传。
- **云同步**：这份包不带云同步，没有账号体系，也没有任何统计埋点。

## 包内文件

```
index.html                  全部界面与流程，单文件，无构建
apk/ShiFangFa-public.apk    安卓安装包
mobile-shell/               APK 壳工程：Java 源码与打包脚本
release-sync.js             同步客户端——读到空凭据就自行停用
sync-config.js              凭据壳（本包内为空）
vendor/cloudbase.full.js    同步客户端用到的 SDK（本包内不会被加载）
```

想自己打包 APK：`python mobile-shell/build-apk.py --public`（需要 Android 构建环境；脚本会把仓库根的 `index.html` 原样打进包里）。

## 关于问句的出处

页面上的引导问句沿用圣多纳释放法（Sedona Method）练习里的说法——这套方法源于 Lester Levenson，后经 Hale Dwoskin 整理成书推广。六步骤与情绪表的中英文取自 1992 年官方练习本（Sedona Workbook v2），英文逐字照抄，中文按原版译；中文句式另参考公开课程转录。本项目与该方法的任何权利人没有关系，也不代替课程本身。

## 这个工具不做的事

它不是医疗或心理治疗的替代品，不给诊断，不做疗效承诺。它只把一套练习步骤摆在屏幕上，提醒你别漏掉某一问。情绪持续低落或有伤害自己的想法，请找专业帮助。

## 权利声明

本仓库内容默认保留所有权利：欢迎从 Releases 下载自用，未经许可请勿再分发、改名发布或商用。

## English

**Sedona Release Method — self-guided releasing, offline.**

This repo holds a single-file web app (`index.html`) and an Android APK (`apk/ShiFangFa-public.apk`). Open the HTML in any browser, or install the APK. No account, no sign-up, and it works in Chinese and English (switch in the「外观 / Appearance」panel).

- **Guide** — write one goal sentence, then answer the questions by tapping options. Close the page any time and continue later.
- **Eight lessons / Sheets / Harvest** — a lesson-by-lesson course, free-form notes, and a journal you can export as a TXT file.

Your entries live only in this device's browser storage. The web build makes no network requests at all; the APK only checks the author's static host for updates. There is no cloud sync and no analytics.

This tool is not a substitute for medical or psychological care, offers no diagnosis, and makes no promises of results. If you feel persistently low or have thoughts of harming yourself, please seek professional help.

Questions and feedback are welcome in [Issues](https://github.com/Venus521/sedona-release-method/issues) — English is fine.
