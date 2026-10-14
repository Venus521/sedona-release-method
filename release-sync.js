/* 释放法·收获本云同步（腾讯云开发 CloudBase）— 2026-10-02 复用 ani-tracker 同步引擎，仅同步收获本
   面板 ② 区容器 #cbArea 由本文件渲染；登录后数据自动上云，换设备登录即恢复。

   平台现状与 v2.6.1 修复说明：
   - CloudBase 不允许「用户名+密码」直接注册（SDK 直接拒绝：You must provide either an email or phone number）；
     正确流程为先建邮箱账号，之后才能绑定用户名。
   - 注册：邮箱 + 密码 → 发邮箱验证码 → 验证通过建号（可顺手绑定一个用户名）。
   - 登录：邮箱 + 密码，或（绑定后）用户名 + 密码，两种均可。
   - 若云端尚未开启「邮箱登录」，界面会给出中文说明与控制台直达链接（新窗口打开）。
   策略：新账号先手动"立即上传/从云端恢复"一次，之后 save() 防抖自动上传。 */
(function(){
  var CFG  = window.RELEASE_SYNC_CFG || {};   /* 凭据全部在 sync-config.js：源码不存字面量（10-03 外置） */
  var ENV  = CFG.ENV  || '';
  var REGION = CFG.REGION || 'ap-shanghai';
  var KEY  = CFG.KEY  || '';
  var COLL = 'release_harvest';
  var HARVEST_KEY = 'release-harvest-v1';   /* 收获本（与 index.html 的 LS_HARVEST 一致） */
  /* v2.16.0 同步面从「只有收获本」扩到全量：台账／工作本／主题／玩法档各一把钥匙，
     键名与 index.html 里的 LS / LS_SHEETS / LS_WANTS 及主题、墓碑常量逐一对齐。 */
  var LEDGER_KEY = 'release-ledger-v1';
  var SHEETS_KEY = 'release-sheets-v1';
  var TOPIC_KEY  = 'release-sheets-topics-v1';
  var WANTS_KEY  = 'release-wants-v1';
  var LG_TOMB    = 'rf_ledger_tomb';
  var SH_TOMB    = 'rf_sheets_tomb';
  var LS_LAST = 'at_cb_last';
  var LOGK = 'at_sync_log';
  var LS_REG = 'at_reg_pending';   /* v2.11.0：注册中的「待验证」状态，持久化后可跨刷新续用 */
  var CONSOLE_URL = 'https://tcb.cloud.tencent.com/dev?envId=' + ENV + '#/identity/login-manage';
  var VERSION = ''; /* v2.13.0：版本单一源——运行期取主页面 AT_VERSION，本文件不再自立版本号 */

  function lg(kind, title, st){ try{ var all = JSON.parse(localStorage.getItem(LOGK) || '[]'); all.push({ id: 'LG' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), at: new Date().toISOString(), kind: kind, title: title, ok: st === 'ok' ? 1 : 0, fail: st === 'ok' ? 0 : 1, items: [], note: '', rolledBack: false }); localStorage.setItem(LOGK, JSON.stringify(all.slice(-80))); }catch(e){} }
  var app = null, auth = null, db = null, _timer = null;
  var _pendingVerify = null, _pendingEmail = '', _cooldown = null;
  var _sending = false;   /* v2.11.0：发信进行中（5~6 秒），用于真正锁住按钮 */

  function escH(s){ return String(s == null ? '' : s).replace(/[&<>"']/g, function(c){
    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }

  function boot(){
    if (app) return true;
    if (!CFG.ENV || !CFG.KEY) return false;   /* 缺 sync-config.js：云同步停用 */

    if (!window.cloudbase) return false;
    try {
      app = cloudbase.init({ env: ENV, region: REGION, accessKey: KEY, auth: { detectSessionInUrl: true } });
      auth = app.auth; db = app.database();
    } catch (e) { app = null; return false; }
    return true;
  }

  /* 错误翻译：把 CloudBase/SDK 报错翻成用户能看懂的中文；ctx: signup|login|generic
     ------------------------------------------------------------------
     v2.11.0 重写。旧版只把 message/status/helpMessage 拼成一串去正则匹配，
     实测有两个问题：
       ① 真实验证码错误的 message 是 Go 的格式化残留：
          "无效的验证码%!(EXTRA string=rpc error: code = InvalidArgument desc = ...)"
          旧版能命中，靠的是 helpMessage 里的中文「校验失败」——
          也就是说 message 本身压根没被认出来，腾讯一改文案就退化成吐乱码。
       ② 6 个不同的错误原文（缺 messageId / 验证码错 / 验证码过期 …）都会带上
          "[CloudBase Auth]" 前缀和一大段排错建议，正则很容易撞车。
     新策略：优先读结构化字段 category / status / errorCode（这些是稳定枚举），
             字段认不出来时才退回文本匹配；最后统一剥掉 %!(EXTRA ...) 残留。 */

  /* 剥掉 Go fmt 的 %!(EXTRA ...) / %!s(MISSING) 之类残渣，只留人话 */
  function cleanMsg(s){
    s = String(s || '');
    var i = s.indexOf('%!');
    if (i >= 0) s = s.slice(0, i);          /* 残渣都在尾部，砍掉即可 */
    return s.replace(/\s+/g, ' ').trim();
  }

  function errText(e, ctx){
    var s = '', c = '', d = '', h = '', cat = '', ec = '';
    try { s = String((e && (e.message || e.errMsg || e.msg)) || ''); } catch (x) {}
    try { c = String((e && (e.status || e.code || e.error_code)) || ''); } catch (x) {}
    try { d = String((e && (e.error_description || e.description)) || ''); } catch (x) {}
    try { h = String((e && e.helpMessage) || ''); } catch (x) {}
    try { cat = String((e && e.category) || ''); } catch (x) {}
    try { ec = String((e && e.errorCode) || ''); } catch (x) {}
    var all = s + ' ' + c + ' ' + d + ' ' + h + ' ' + cat;

    /* —— 第一优先：结构化字段（稳定，不受文案影响）—— */
    if (cat === 'VERIFICATION_FAILED') {
      /* 注意：细分「过期」还是「不对」只能看文本，但**不能拿 helpMessage 去判** ——
         实测的 helpMessage 是一份「常见原因清单」，里面同时列了
         「验证码已过期」「验证码输入不正确」两条，拿它匹配必然误判成「过期」。
         所以这里只认 message/status 这两处真正的结论性文本。 */
      var concl = s + ' ' + c + ' ' + d;
      if (/expire|过期|失效/i.test(concl)) return '验证码已过期，请重新获取';
      return '验证码不正确，请检查后重填';
    }
    if (cat === 'INVALID_CREDENTIALS' || c === 'invalid_username_or_password') return '邮箱/用户名或密码不正确';
    if (cat === 'USER_ALREADY_EXISTS' || c === 'user_already_exists') return '这个邮箱已经注册过了，可直接登录';
    if (cat === 'RATE_LIMITED' || ec === '429') return '操作太频繁，请等 1 分钟再试';

    /* —— 第二优先：注册上下文里「账号不存在」= 云端没开邮箱登录 —— */
    if (/provider email not found/i.test(all)) return 'PROVIDER_OFF';
    if (ctx === 'signup' && /not[_ ]?found|不存在|USER_NOT_FOUND/i.test(all)) return 'PROVIDER_OFF';

    /* —— 第三优先：其余按关键词兜底 —— */
    if (all.indexOf('You must provide either an email or phone number') >= 0) return '请填写有效邮箱（当前账号体系使用邮箱注册）';
    if (/messageId is required|token is required/i.test(all)) return '验证凭证已失效，请重新点「获取验证码」';
    if (/invalid_verification_code|验证码(错误|不正确|无效|校验失败)|verification code does not match/i.test(all)) return '验证码不正确，请检查后重填';
    if (/verification_?code.*(expired|过期)|expired/i.test(all)) return '验证码已过期，请重新获取';
    if (/too many|frequent|频繁|限流|exceeded|429/i.test(all)) return '操作太频繁，请等 1 分钟再试';
    if (/user_already_exists|already (been )?registered|已被注册|已注册|已存在/i.test(all)) return '这个邮箱已经注册过了，可直接登录';
    if (/you already have username/i.test(all)) return '这个账号已经绑定过用户名了（一个账号只能绑一次）';
    if (/does not match regex pattern|invalid.*EditProfileRequest.Username/i.test(all)) return '平台规则：登录用户名须 6–25 位、小写英文开头（不支持中文）';
    if (/invalid.*username|用户名(格式|不合规)|INVALID_USERNAME/i.test(all)) return '平台规则：登录用户名须 6–25 位、小写英文开头（不支持中文）';
    if (/password/i.test(all) && /invalid|格式|weak|至少|不符|too short/i.test(all)) return '云端不收这个密码（本机只要求 6–32 位，云端可能还要求含字母和数字）——加长或混入字母再试';
    if (/network|Failed to fetch|timeout|超时|NetworkError|ERR_/i.test(all)) return '网络不给力，请检查网络后重试';
    if (/not_found|不存在|USER_NOT_FOUND/i.test(all)) return ctx === 'login' ? '账号不存在或未注册——请先注册一个' : '找不到对应账号（可先注册）';

    /* —— 最后：把 message 洗干净再给用户，绝不吐 %!(EXTRA ...) —— */
    var clean = cleanMsg(s);
    if (clean && clean !== '[object Object]') return clean;
    try { var jx = JSON.stringify(e); if (jx && jx !== '{}' && jx !== 'null') return cleanMsg(jx).slice(0, 220); } catch (x) {}
    return '操作失败，请稍后重试';
  }

  function providerOffHtml(){
    return '云端还没开启「邮箱登录」，暂时无法发送验证码。<br/>开通方法（约 2 分钟）：云开发控制台 → <b>身份认证 → 登录方式</b> → 开启「邮箱登录」，并按提示配置发件邮箱（SMTP）。<br/>' +
      '<a href="' + CONSOLE_URL + '" target="_blank" rel="noopener" class="cb-link">在浏览器打开控制台去开启 →</a>';
  }

  function isEmail(v){ return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(String(v || '').trim()); }
  function pwdOk(p){ p = String(p || ''); return p.length >= 6 && p.length <= 32; }
  function userOk(u){
    /* CloudBase 后端正则（实测）：^$|^[a-z][0-9a-z:_-]{5,24}$ —— 小写字母开头，6–25 位 */
    return /^[a-z][0-9a-z:_-]{5,24}$/.test(String(u || '').trim());
  }

  var _busy = '', _busyKind = 'dim';  /* 进行中的那句话：重绘以后还得从这儿补回去 */
  var _sessErr = '';  /* 最近一次 sess() 为什么没查到；空串＝查得动，真的没登录 */
  var _userCache = null; /* v2.13.1 修复：acctStatus() 同步调 current()，而 sess() 是 async——返回的 Promise 恒为真值，状态条永远显示「已登录」。这里缓存最近一次 sess() 的真实结果供 current() 同步读取。 */
  async function sess(){
    _sessErr = '';
    if (!boot()) { _userCache = null; _sessErr = '云同步组件没加载（网络不通或脚本没到位）'; return null; }
    try {
      var r = await auth.getSession();
      try { window._cbSessionDump = JSON.stringify((r && r.data) || {}).slice(0, 2000); } catch (e0) {}
      var s = r && r.data && r.data.session;
      if (!s) { _userCache = null; return null; }
      var u = (r.data.user) || s.user || { uid: (s && (s.uid || s.sub)) || '' };
      _userCache = u;
      return u;
    } catch (e) { _userCache = null; _sessErr = errText(e); return null; }
  }

  function uidOf(u){
    if (!u) return '';
    var cands = [u.uid, u.sub, u.userid, u.userId, u.identifier, u.customUserId];
    for (var i = 0; i < cands.length; i++) { if (cands[i]) return String(cands[i]); }
    try {
      for (var k in u) {
        if (!Object.prototype.hasOwnProperty.call(u, k)) continue;
        var v = u[k];
        if (typeof v === 'string' && /^\d{15,25}$/.test(v)) return v;
        if (v && typeof v === 'object') {
          var c2 = [v.uid, v.sub, v.userid, v.userId];
          for (var j = 0; j < c2.length; j++) { if (c2[j]) return String(c2[j]); }
        }
      }
    } catch (e) {}
    return '';
  }

  function userName(u){
    if (!u) return '';
    var n = u.username || u.nickname || u.nickName || u.name || '';
    if (n) return n;
    if (u.email) return u.email;
    var id = uidOf(u);
    return id ? ('用户' + id.slice(-4)) : '账号';
  }

  function docId(uid){ return 'u_' + uid; }

  async function peek(){
    var u = await sess(); if (!u) return null;
    var uid = uidOf(u); if (!uid) return null;
    try {
      var r = await db.collection(COLL).where({ ownerId: uid }).limit(2).get();
      return ((r && r.data) || [])[0] || null;
    } catch (e) { return null; }
  }

  /* v2.13.0：凭证/机器级 key 永不上云（复用主页面共享正则） */
  var SKIP_RE = (window.AT_BACKUP && window.AT_BACKUP.secretRe)
    ? window.AT_BACKUP.secretRe
    : /^credentials_|^user_info_|^at_bgm_token$|^at_net_relay$|^device_id$|^tr_dav$|^at_ai_cfg$/;
  function packAll(){
    var all = { app: 'release-full', version: 5, exportedAt: new Date().toISOString(), data: {} };
    var v = localStorage.getItem(HARVEST_KEY);
    if (v !== null) all.data[HARVEST_KEY] = v;
    /* v2.15.0 外观三键随包上云：电脑/手机配色保持一致（最新改动者赢，见 applyCloudMerge）。
       配色只是颜色值，不是凭据，不落 SKIP 红线。 */
    ['release-look-v1', 'release-look-custom-v1', 'rf_look_meta'].forEach(function(k){
      var s = localStorage.getItem(k);
      if (s !== null) all.data[k] = s;
    });
    /* v2.16.0 释放的时间／次数／目标都在台账行与工作本条目里：这两份（加主题、玩法）一并随包上云。 */
    [LEDGER_KEY, SHEETS_KEY, TOPIC_KEY, WANTS_KEY, 'rf_wants_meta'].forEach(function(k){
      var s = localStorage.getItem(k);
      if (s !== null) all.data[k] = s;
    });
    /* 删过的条目墓碑也得随包上去：合并那一侧读的就是这个键。以前没带，
       另一台设备删掉的收获下次合并全部复活（10-05 实测「合并用不了」根因之一）。 */
    ['rf_harvest_tomb', LG_TOMB, SH_TOMB].forEach(function(k){
      var s = localStorage.getItem(k);
      if (s !== null) all.data[k] = s;
    });
    return JSON.stringify(all);
  }

  /* ===== 按条目 id 三方合并（收获本／台账／工作本共用一套） =====
    旧行为：download() 把云端 payload 全量盲写本机。
    新行为：本机/云端各一份条目数组 + 各自删除墓碑，按 id 取较新者合并，
    任一侧删过的条目不再出现，不会冲掉另一台设备的修改。 */
  function applyCloudMerge(payloadStr){
    var res = { changedAny: false, onlyCloud: 0, merged: 0, dropped: 0 };
    var all; try { all = JSON.parse(payloadStr); } catch (e) { return res; }
    if (!all || all.app !== 'release-full') return res;
    var rem = all.data || {};
    var TK = 'rf_harvest_tomb';
    try {
      var lList = JSON.parse(localStorage.getItem(HARVEST_KEY) || '[]');
      var cList = JSON.parse(rem[HARVEST_KEY] || '[]');
      var tL = JSON.parse(localStorage.getItem(TK) || '{}');
      var tC = JSON.parse(rem[TK] || '{}');
      if (Array.isArray(lList) && Array.isArray(cList)) {
        var m = mergeHarvest(lList, cList, tL, tC);
        res.onlyCloud = m.stats.onlyCloud; res.merged = m.stats.merged; res.dropped = m.stats.dropped;
        if (JSON.stringify(m.list) !== JSON.stringify(lList)) { localStorage.setItem(HARVEST_KEY, JSON.stringify(m.list)); res.changedAny = true; }
        if (JSON.stringify(m.tomb) !== JSON.stringify(tL)) { localStorage.setItem(TK, JSON.stringify(m.tomb)); res.changedAny = true; }
      }
    } catch (e) { lg('cloud', '收获本合并异常：' + ((e && e.message) || e), 'warn'); }
    /* 台账（每次释放的 开始时间／用时／轮数／目标句／情绪／想要）逐条合并，本机与云端谁的新取谁 */
    try {
      var lr = JSON.parse(localStorage.getItem(LEDGER_KEY) || '[]');
      var cr = JSON.parse(rem[LEDGER_KEY] || '[]');
      if (Array.isArray(lr) && Array.isArray(cr)) {
        var m2 = mergeRows(lr, cr, JSON.parse(localStorage.getItem(LG_TOMB) || '{}'), JSON.parse(rem[LG_TOMB] || '{}'),
          function(r){ return r.id; },
          function(a, b){ return String((b.date||'')+(b.time||'')).localeCompare(String((a.date||'')+(a.time||''))); });
        res.ledgerAdded = m2.stats.onlyCloud; res.ledgerDropped = m2.stats.dropped;
        if (m2.changed) { localStorage.setItem(LEDGER_KEY, JSON.stringify(m2.list)); localStorage.setItem(LG_TOMB, JSON.stringify(m2.tomb)); res.changedAny = true; }
      }
    } catch (e) { lg('cloud', '台账合并异常：' + ((e && e.message) || e), 'warn'); }
    /* 工作本：条目按「表+条目钥匙」合并，每条自己那些走链记录跟着条目走 */
    try {
      var ls = JSON.parse(localStorage.getItem(SHEETS_KEY) || '{}');
      var cs = JSON.parse(rem[SHEETS_KEY] || '{}');
      if (ls && cs && typeof ls === 'object' && typeof cs === 'object') {
        var m3 = mergeSheets(ls, cs, JSON.parse(localStorage.getItem(SH_TOMB) || '{}'), JSON.parse(rem[SH_TOMB] || '{}'));
        res.sheetsAdded = m3.stats.onlyCloud; res.sheetsDropped = m3.stats.dropped;
        if (m3.changed) { localStorage.setItem(SHEETS_KEY, JSON.stringify(m3.sheets)); localStorage.setItem(SH_TOMB, JSON.stringify(m3.tomb)); res.changedAny = true; }
      }
    } catch (e) { lg('cloud', '工作本合并异常：' + ((e && e.message) || e), 'warn'); }
    /* 主题逐表比改动时间；谁的新用谁的（清空也带时间戳，不会被对面又合并回来） */
    try {
      var lt = JSON.parse(localStorage.getItem(TOPIC_KEY) || '{}');
      var ct = JSON.parse(rem[TOPIC_KEY] || '{}');
      var mt = mergeTopics(lt, ct);
      if (mt.changed) { localStorage.setItem(TOPIC_KEY, JSON.stringify(mt.map)); res.changedAny = true; }
    } catch (e) { lg('cloud', '主题合并异常：' + ((e && e.message) || e), 'warn'); }
    /* v2.15.0 外观合并：云端外观改动时间比本机已应用的新 → 整套取云端（最新改动者赢）。
       配色不搞逐条三方合并；rf_look_applied_at 防止把自己刚上传的又应用一遍。 */
    try {
      var lkMeta = rem['rf_look_meta'] ? JSON.parse(rem['rf_look_meta']) : null;
      var lkApplied = localStorage.getItem('rf_look_applied_at') || '';
      if (lkMeta && lkMeta.at && lkMeta.at > lkApplied && rem['release-look-v1']) {
        localStorage.setItem('release-look-v1', rem['release-look-v1']);
        if (rem['release-look-custom-v1']) localStorage.setItem('release-look-custom-v1', rem['release-look-custom-v1']);
        localStorage.setItem('rf_look_applied_at', lkMeta.at);
        res.lookChanged = true;
      }
    } catch (e) { /* 外观合并失败不连累收获本 */ }
    /* 玩法档（哪一版教材、什么顺序、放完往下跳到哪儿）同样最新改动者赢，整份取云端 */
    try {
      var wMeta = rem['rf_wants_meta'] ? JSON.parse(rem['rf_wants_meta']) : null;
      var wApplied = localStorage.getItem('rf_wants_applied_at') || '';
      if (wMeta && wMeta.at && wMeta.at > wApplied && rem[WANTS_KEY]) {
        localStorage.setItem(WANTS_KEY, rem[WANTS_KEY]);
        localStorage.setItem('rf_wants_applied_at', wMeta.at);
        res.changedAny = true;
      }
    } catch (e) { /* 玩法合并失败不连累数据 */ }
    return res;
  }

  /* 通用按钥匙三方合并：任一端独有的保留，两端都有的取较新者（updatedAt，其次 ts），
     任一端删过的（在墓碑里）不再出现。keyOf 给钥匙，sortCmp 给顺序。 */
  function mergeRows(lList, cList, tL, tC, keyOf, sortCmp){
    var stats = { onlyCloud: 0, merged: 0, dropped: 0 };
    var tomb = {};
    [tL, tC].forEach(function(t){ for (var k in t) if (t.hasOwnProperty(k)) tomb[k] = true; });
    var byK = {}, seenL = {};
    (lList || []).forEach(function(it){ if (it && keyOf(it)) seenL[keyOf(it)] = true; });
    (lList || []).concat(cList || []).forEach(function(it){
      if (!it) return;
      var k = keyOf(it);
      if (!k) return;
      if (tomb[k]) { stats.dropped++; return; }
      var cur = byK[k];
      if (!cur) { byK[k] = it; if (!seenL[k]) stats.onlyCloud++; }
      else {
        var lu = cur.updatedAt || cur.ts || '', cu = it.updatedAt || it.ts || '';
        if (cu > lu) byK[k] = it;
        stats.merged++;
      }
    });
    var list = Object.keys(byK).map(function(k){ return byK[k]; });
    if (sortCmp) list.sort(sortCmp);
    return { list: list, tomb: tomb, stats: stats,
      changed: JSON.stringify(list) !== JSON.stringify(lList) || JSON.stringify(tomb) !== JSON.stringify(tL) };
  }
  function mergeHarvest(lList, cList, tL, tC){
    return mergeRows(lList, cList, tL, tC, function(x){ return x.id; },
      function(a, b){ return String(a.ts || '').localeCompare(String(b.ts || '')); });
  }

  /* 工作本：条目钥匙 = 表 id + 条目自己的 c（没有 c 的老条目退回 n+本机 id）。
     合并完按表重新分桶、按组与句柄排序，桶的顺序固定下来才不会因键序不同而误报「有变化」。 */
  function sheetNonce(it){ return it.c ? it.c : ('n' + it.id); }
  function flatSheets(o, local){
    var out = [];
    Object.keys(o || {}).forEach(function(sid){
      var arr = o[sid];
      if (!Array.isArray(arr)) return;
      arr.forEach(function(it){
        if (!it) return;
        out.push({ sid: sid, it: it, k: sid + '|' + sheetNonce(it), local: !!local });
      });
    });
    return out;
  }
  function mergeSheets(lRaw, cRaw, tL, tC){
    var stats = { onlyCloud: 0, merged: 0, dropped: 0 };
    var tomb = {};
    [tL, tC].forEach(function(t){ for (var k in t) if (t.hasOwnProperty(k)) tomb[k] = true; });
    var byK = {};
    flatSheets(lRaw, true).concat(flatSheets(cRaw, false)).forEach(function(e){
      if (tomb[e.k]) { stats.dropped++; return; }
      var cur = byK[e.k];
      if (!cur) { byK[e.k] = e; if (!e.local) stats.onlyCloud++; }
      else {
        var lu = (cur.it && cur.it.updatedAt) || '', cu = (e.it && e.it.updatedAt) || '';
        if (cu > lu) byK[e.k] = e;
        stats.merged++;
      }
    });
    var buckets = {};
    Object.keys(byK).forEach(function(k){
      var e = byK[k];
      if (!buckets[e.sid]) buckets[e.sid] = [];
      buckets[e.sid].push(e.it);
    });
    var sheets = {};
    Object.keys(buckets).sort().forEach(function(sid){
      sheets[sid] = buckets[sid].slice().sort(function(a, b){
        return ((a.g || 0) - (b.g || 0)) || ((a.id || 0) - (b.id || 0));
      });
    });
    return { sheets: sheets, tomb: tomb, stats: stats,
      changed: JSON.stringify(sheets) !== JSON.stringify(lRaw) || JSON.stringify(tomb) !== JSON.stringify(tL) };
  }

  /* 主题：每张表各比各的改动时间，谁的新用谁的；值一律写成 {t,u} */
  function mergeTopics(lT, cT){
    var ks = {}, map = {};
    Object.keys(lT || {}).concat(Object.keys(cT || {})).forEach(function(k){ ks[k] = true; });
    Object.keys(ks).sort().forEach(function(k){
      var a = (lT || {})[k], b = (cT || {})[k];
      if (a && typeof a === 'string') a = { t: a, u: '' };
      if (b && typeof b === 'string') b = { t: b, u: '' };
      var pick = !b ? a : (!a ? b : (((b.u || '') > (a.u || '')) ? b : a));
      if (pick) map[k] = { t: String(pick.t || ''), u: String(pick.u || '') };
    });
    return { map: map, changed: JSON.stringify(map) !== JSON.stringify(lT || {}) };
  }

  /* 云端是否领先本机（有本机未合并过的更新） */
  async function remoteAhead(){
    var last = lastInfo();
    if (!last || !last.at) return null;
    var doc = await peek();
    if (doc && doc.updatedAt && doc.payload && new Date(doc.updatedAt) > new Date(last.at)) return doc;
    return null;
  }

  async function upload(silent){
    var u = await sess();
    if (!u) throw new Error('未登录');
    var uid = uidOf(u);
    if (!uid) throw new Error('登录态缺少 uid');
    /* v2.13.0 先合后传：云端有本机没见过的更新 → 合并进本机并整页刷新，本次上传作废
       （刷新后数据已是双方并集，不会丢另一台设备的修改） */
    try {
      var ahead = await remoteAhead();
      if (ahead) {
        var st = applyCloudMerge(ahead.payload);
        try { localStorage.setItem(LS_LAST, JSON.stringify({ at: ahead.updatedAt })); } catch (e) {}
        lg('cloud', '上传前发现云端更新，已合并（+' + st.onlyCloud + ' 部自云端，' + st.merged + ' 部并集，-' + st.dropped + ' 部已删）' + (st.changedAny ? '，刷新页面' : ''), 'ok');
        if (st.lookChanged && window.__atLook) { try { window.__atLook(); } catch (e) {} }
        if (st.changedAny) { location.reload(); return; }
      }
    } catch (e) { /* 检查失败不阻断上传，走原覆盖逻辑 */ }
    var payload = packAll();
    var rec = { ownerId: uid, payload: payload, app: 'release-full', updatedAt: new Date().toISOString() };
    /* set 会被规则按 update 评估（新文档无 owner 可查 → 拒），所以先 add 建、已存在再 update */
    try {
      await db.collection(COLL).add(Object.assign({ _id: docId(uid) }, rec));
    } catch (e1) {
      var r = await db.collection(COLL).doc(docId(uid)).update(rec);
      if (!(r && r.updated > 0)) throw new Error('上传未生效（' + ((e1 && e1.message) || 'update 返回 0') + '）');
    }
    try { localStorage.setItem(LS_LAST, JSON.stringify({ at: rec.updatedAt })); } catch (e) {}
    lg('cloud', '云同步：上传成功', 'ok');
    if (!silent) toast('已上传到云端 ✔');
  }

  async function download(){
    var u = await sess();
    if (!u) throw new Error('未登录');
    var uid = uidOf(u);
    if (!uid) throw new Error('登录态缺少 uid');
    var r;
    try { r = await db.collection(COLL).where({ ownerId: uid }).limit(2).get(); } catch (e) { throw new Error('读取云端失败（网络或登录过期，请退出重登）'); }
    var doc = ((r && r.data) || [])[0];
    if (!doc || !doc.payload) throw new Error('云端还没有备份——先在本机点「立即上传」');
    /* v2.13.0：由「全量覆盖本机」改为「逐条合并」——本机更新的修改不会被云端旧数据冲掉 */
    var st = applyCloudMerge(doc.payload);
    try { localStorage.setItem(LS_LAST, JSON.stringify({ at: doc.updatedAt || new Date().toISOString() })); } catch (e) {}
    lg('cloud', '云同步：合并完成（自云端 +' + st.onlyCloud + '、并集 ' + st.merged + '、删 ' + st.dropped + '）', 'ok');
    if (st.lookChanged && window.__atLook) { try { window.__atLook(); } catch (e) {} }
    return { changed: !!st.changedAny, lookChanged: !!st.lookChanged, stats: st };
  }

  function lastInfo(){
    try { return JSON.parse(localStorage.getItem(LS_LAST) || 'null'); } catch (e) { return null; }
  }

  /* --- 注册状态持久化（v2.11.0 新增）---
     ------------------------------------------------------------------
     为什么要做：signUp() 只返回 { data: { verifyOtp: 函数 } } ——
     一个闭包，**没有任何明文凭证**，只能存在内存变量里。
     但正常注册流程是：点「获取验证码」→ 去邮箱收信（几十秒到几分钟）→ 回来填码。
     这中间只要刷新页面 / 切后台被系统回收 / 误触返回，闭包就没了，
     用户只能从头再来，而且完全不知道为什么会失败。

     解法：resend({email, type:'signup'}) 会返回一个可持久化的 messageId
     （实测是一个 JWT，payload = { e: 邮箱, exp: +10分钟, pj: 环境ID }）。
     auth.verifyOtp({ token, messageId }) 是独立方法，能拿它重建验证能力。
     所以：把 { email, messageId, at } 存进 localStorage，刷新后照样能验证。

     注意：仍然优先用内存里的闭包（少一次网络往返）；
     只有闭包不存在（比如刚刷新过）时才走 messageId 这条路。 */
  function saveRegPending(email, messageId){
    try {
      if (!email) { localStorage.removeItem(LS_REG); return; }
      localStorage.setItem(LS_REG, JSON.stringify({
        email: email, messageId: messageId || '', at: Date.now(),
      }));
    } catch (e) {}
  }
  function loadRegPending(){
    try {
      var p = JSON.parse(localStorage.getItem(LS_REG) || 'null');
      if (!p || !p.email || !p.at) return null;
      /* 验证码默认 10 分钟有效，留 30 秒余量；过期就丢掉，避免用户对着死凭证白填 */
      if (Date.now() - p.at > 9.5 * 60 * 1000) { localStorage.removeItem(LS_REG); return null; }
      return p;
    } catch (e) { return null; }
  }
  function clearRegPending(){
    try { localStorage.removeItem(LS_REG); } catch (e) {}
    _pendingVerify = null; _pendingEmail = '';
  }
  /* 从 persist 态恢复出「还能不能验证」的能力判断 */
  function regResumable(){
    if (_pendingVerify || _pendingEmail) return true;   /* 内存里有闭包 */
    var p = loadRegPending();
    return !!(p && p.messageId);                         /* 或者有可用的 messageId */
  }

  /* --- 注册（v2.6.1 新链路）：邮箱+密码 → 发验证码；返回 verifyOtp 句柄 --- */
  async function startSignUp(email, password){
    if (!boot()) throw new Error('云组件未加载（需联网）');
    var r = await auth.signUp({ email: email, password: password });
    if (r && r.error) throw r.error;
    if (!(r && r.data && typeof r.data.verifyOtp === 'function')) throw new Error('验证码发送失败，请稍后重试');

    /* 顺手取一个可持久化的 messageId，这样用户收信期间刷新也不会白填。
       拿不到不影响主流程（仍可用内存闭包验证），所以失败就静默降级。 */
    var mid = '';
    try {
      var rs = await auth.resend({ email: email, type: 'signup' });
      if (rs && rs.data && rs.data.messageId) mid = rs.data.messageId;
    } catch (eR) {}
    saveRegPending(email, mid);
    return r.data.verifyOtp;
  }

  /* 完成注册：验证码校验 → 建号（邮箱已注册时会直接登录）→ 可选绑定用户名 */
  async function finishSignUp(verifyFn, code, username, email){
    var r;
    if (verifyFn) {
      r = await verifyFn({ token: code });
    } else {
      /* 刷新过 → 内存闭包没了，改用 messageId 重建 */
      var p = loadRegPending();
      if (!p || !p.messageId) throw new Error('验证凭证已失效，请重新点「获取验证码」');
      r = await auth.verifyOtp({ token: code, messageId: p.messageId });
    }
    if (r && r.error) throw r.error;
    /* 注意：SDK 这一步失败时是「返回 error」而不是「抛异常」，上面已拦。
       成功时 data.user/data.session 可能仍然为 null（要看具体版本），
       所以用 sess() 复核一次真实登录态，别被 data 的空壳骗了。 */
    var u = await sess();
    if (!u) throw new Error('验证成功但登录态没建立起来，请用邮箱 + 密码登录一次');
    var bound = '';
    if (username) {
      bound = await bindUsername(username);
    }
    clearRegPending();
    return { user: u, bound: bound };
  }

  /* 绑定用户名（注册时可选、登录后也能补绑）—— 抽出来两处共用 */
  async function bindUsername(username){
    if (!userOk(username)) throw new Error('平台规则：登录用户名须 6–25 位、小写英文开头（不支持中文）');
    var cu = auth.currentUser || (await auth.getCurrentUser());
    var t = (cu && typeof cu.updateUsername === 'function') ? cu : (cu && cu.data && (cu.data.user || cu.data)) || cu;
    if (!t || typeof t.updateUsername !== 'function') throw new Error('登录态异常，请退出重登后再试');
    await t.updateUsername(username);
    return username;
  }

  /* --- 忘记密码（v2.17.0）：邮箱 → 云端发验证码，拿回一个改密句柄 ---
     SDK 的 resetPasswordForEmail 不返回布尔，而是返回 data.updateUser 闭包：
     拿它带 {nonce: 邮件里的验证码, password: 新密码} 调用，云端校验通过后顺带把这台设备登录上。 */
  async function startReset(email){
    if (!boot()) throw new Error('云组件未加载（需联网）');
    var r = await auth.resetPasswordForEmail(email);
    if (r && r.error) throw r.error;
    var fn = r && r.data && r.data.updateUser;
    if (typeof fn !== 'function') throw new Error('云端没吐出验证码（这个邮箱大概没注册过）');
    return fn;
  }
  async function finishReset(updateUser, code, password){
    var r = await updateUser({ nonce: code, password: password });
    if (r && r.error) throw r.error;
    var u = await sess();
    if (!u) throw new Error('密码已改但没自动登录上，请用新密码登录');
    return u;
  }

  /* --- 登录：邮箱或用户名 + 密码 --- */
  async function signIn(idOrEmail, password){
    if (!boot()) throw new Error('云组件未加载（需联网）');
    var org = String(idOrEmail || '').trim();
    var payload = isEmail(org) ? { email: org, password: password } : { username: org, password: password };
    var r = await auth.signInWithPassword(payload);
    if (r && r.error) throw r.error;
    var u = await sess();
    if (!u) throw new Error('登录态未生效，请重试');
    return u;
  }

  async function signOut(){ _userCache = null; if (auth) { try { await auth.signOut(); } catch (e) {} } }

  /* save() 时调用：同步过至少一次才自动上传，避免新设备空数据覆盖云端 */
  function autosync(){
    if (!lastInfo()) return;
    sess().then(function(u){
      if (!u) return;
      clearTimeout(_timer);
      _timer = setTimeout(function(){ upload(true).catch(function(){}); }, 5000);
    }).catch(function(){});
  }

  function fmtAt(iso){
    try { return new Date(iso).toLocaleString('zh-CN', { hour12: false }); } catch (e) { return iso || ''; }
  }

  function mount(box){
    var area = box.querySelector('#cbArea');
    if (!area) return;
    if (!(window.RELEASE_SYNC_CFG && window.RELEASE_SYNC_CFG.ENV && window.RELEASE_SYNC_CFG.KEY)) {

      area.innerHTML = '<div class="mini">云同步未配置（缺 sync-config.js）：收获本只存本机，照常可用，导出 JSON 备份不受影响。</div>';

      return;

    }

    if (!boot()) { area.innerHTML = '<div class="mini">云同步组件未加载（本次需联网加载一次，刷新重试）。</div>'; return; }
    /* 面板展开着却空一格＝点了没反应（实测 36px 全空）。先递一句「正在查」，
       再等结果；查砸了要说为什么，并留一颗重试。 */
    retrySess(area);
  }

  function retrySess(area){
    area.innerHTML = '<div class="mini">正在查登录状态…</div>' + statusHtml();
    sess().then(function(u){
      if (!u && _sessErr) { saySessFail(area); return; }
      draw(area, u);
    }).catch(function(){ _sessErr = _sessErr || '请刷新重试'; saySessFail(area); });
  }

  /* 网络挂了和没登录是两回事：以前两种都掉进登录表单，
     用户明明登录着却被催着填邮箱密码（10-05「上传用不了」根因之一）。 */
  function saySessFail(area){
    area.innerHTML = '<div class="mini">没查到登录状态：' + escH(_sessErr || '网络不通')
      + '。要是你本来就登录着，这不是让你重新登录。</div>' + statusHtml()
      + '<div class="row2"><button class="b1 cbfull" id="cbRetry">重试</button></div>';
    var b = area.querySelector('#cbRetry');
    if (b) b.onclick = function(){ retrySess(liveArea(area)); };
  }

  function paint(el, kind){ if (el) el.className = 'msg ' + (kind || 'dim'); }

  /* 状态那句话存在模块里，不只在 DOM 上：上传途中整块重绘（换页签、展开收起都会重画
     这一屏），旧节点被换新的一刻那句话不能凭空消失（10-05「点了没反应」）。 */
  function statusHtml(){
    if (!_busy) return '';
    return '<div class="msg ' + _busyKind + '" id="cbMsg">' + escH(_busy) + '</div>';
  }

  function say(text, kind){
    _busy = text; _busyKind = kind || 'dim';
    var m = document.getElementById('cbMsg');
    if (!m) {
      var area = document.getElementById('cbArea');
      if (!area) return;
      m = document.createElement('div');
      m.id = 'cbMsg';
      area.appendChild(m);
    }
    m.textContent = text;
    m.className = 'msg ' + _busyKind;
  }

  function startCooldown(btn){
    var left = 60; var ori = '获取验证码';
    if (_cooldown) { clearInterval(_cooldown); _cooldown = null; }
    btn.disabled = true;
    btn.textContent = '重发(' + left + 's)';
    _cooldown = setInterval(function(){
      left--;
      if (left <= 0) { clearInterval(_cooldown); _cooldown = null; btn.disabled = false; btn.textContent = ori; }
      else { btn.textContent = '重发(' + left + 's)'; }
    }, 1000);
  }

  /* 上层随时可能重画这一屏（换页签、展开收起都会换新节点），
     抓住旧节点写字＝「上传中」凭空消失。每次落字前重新认一次现节点。 */
  function liveArea(fallback){
    return document.getElementById('cbArea') || fallback;
  }

  function draw(area, u){
    area = liveArea(area);
    if (u) {
      var last = lastInfo();
      area.innerHTML =
        '<div class="mini">已登录：<b class="cb-ink">' + escH(userName(u)) + '</b>。改动会自动同步云端；换设备登录同一账号，点「从云端合并」即可。</div>' +
        '<div class="row2"><button class="b1" id="cbUp">立即上传</button><button class="b2" id="cbDown">从云端合并</button></div>' +
        statusHtml() +
        (last ? '<div class="tiny">上次上传：' + escH(fmtAt(last.at)) + '</div>' : '') +
        '<div class="row2 cb-gap"><button class="b3" id="cbOut">退出登录</button></div>';
      area.querySelector('#cbUp').onclick = async function(){
        say('上传中…', 'dim');
        try {
          /* v2.13.0：不再弹「覆盖云端」确认——upload 内部已先合后传，双方修改都保得住 */
          await upload(false); say('已上传，云端和本机一样了', 'ok');
        } catch (e) { say('上传失败：' + errText(e), 'bad'); }
      };
      area.querySelector('#cbDown').onclick = async function(){
        say('合并中…', 'dim');
        try {
          var res = await download();
          if (res && res.changed) {
            toast('已与云端合并（逐条比对，不会丢另一台设备的修改），即将刷新');
            setTimeout(function(){ location.reload(); }, 800);
          } else {
            say('云端没有需要合并的新数据', 'dim');
          }
        } catch (e) { say('合并失败：' + errText(e), 'bad'); }
      };
      area.querySelector('#cbOut').onclick = async function(){
        var cbu = await sess().catch(function(){ return null; });
        await signOut();
        lg('auth', '退出登录' + (cbu ? '：' + userName(cbu) : ''), 'ok');
        draw(liveArea(area), null);
      };
      return;
    }

    /* ================= v2.14.0：登录 / 注册 单屏（用户指令「还是太复杂」）=================
       v2.11 的双 Tab 版被否：Tab 条 + 「去注册」链接是同一功能的两个入口，纯冗余。
       新版：默认只给登录表单（邮箱+密码+按钮），注册只留底部一行小字入口；
       点「注册」原地换成注册表单（邮箱+密码+验证码），可一键换回。
       保留的实测修复：发信瞬间锁按钮（_sending + disabled）、验证码 4–8 位文案统一、
       刷新后凭 messageId 续注册。 */
    _busy = ''; _busyKind = 'dim';   /* 没登录这一屏不该挂着上一条操作的话 */

    /* —— 密码框一律带「显示」切换（用户指令「加入登陆时的可视密码」）：
       手机端输入法在密码框里看不见自己打了什么，是这个页面上最容易火的一件事。 —— */
    function pwdRow(id, ph, ac){
      return '<div class="cbcode">' +
        '<input id="' + id + '" type="password" autocomplete="' + ac + '" placeholder="' + ph + '"/>' +
        '<button type="button" class="cbget cb-eye" data-eye="' + id + '">显示</button>' +
        '</div>';
    }

    /* —— 登录（默认屏） —— */
    var PANE_LOGIN =
      '<div id="cbPaneLogin" class="cbpane">' +
        '<label>邮箱</label>' +
        '<input id="cbUser" autocomplete="username" placeholder="you@example.com"/>' +
        '<label>密码</label>' +
        pwdRow('cbPass', '请输入密码', 'current-password') +
        '<div class="msg" id="cbMsg"></div>' +
        '<div class="row2"><button class="b1 cbfull" id="cbLogin">登录</button></div>' +
        '<div class="tiny cbfoot">还没有账号？<a href="javascript:;" id="cbToReg">注册</a>' +
          '<span class="cbdot">·</span>忘了密码？<a href="javascript:;" id="cbToReset">重设</a></div>' +
      '</div>';

    /* —— 注册（点「注册」后整屏换成这个） —— */
    var PANE_REG =
      '<div id="cbPaneReg" class="cbpane cbhide">' +
        /* 顶部状态位：刷新续用时在这里说明「还差一步」，而不是把提示塞到表单底下 */
        '<div class="cbnotice cbhide" id="cbRegNotice"></div>' +
        '<label>邮箱</label>' +
        '<input id="cbEmail" type="email" autocomplete="email" placeholder="you@example.com"/>' +
        '<label>密码</label>' +
        pwdRow('cbEmailPass', '6–32 位，数字或字母都行', 'new-password') +
        '<label>验证码（点「获取」后去邮箱收，10 分钟内有效）</label>' +
        '<div class="cbcode">' +
          '<input id="cbCode" inputmode="numeric" autocomplete="one-time-code" placeholder="4–8 位验证码"/>' +
          '<button type="button" class="cbget" id="cbSendCode">获取验证码</button>' +
        '</div>' +
        '<div class="msg" id="cbRegMsg"></div>' +
        '<div class="row2"><button class="b1 cbfull" id="cbFinish">完成注册</button></div>' +
        '<div class="tiny cbfoot">已有账号？<a href="javascript:;" id="cbToLogin">返回登录</a></div>' +
      '</div>';

    /* —— 重设密码（点「重设」后整屏换成这个） —— */
    var PANE_RESET =
      '<div id="cbPaneReset" class="cbpane cbhide">' +
        '<label>注册邮箱</label>' +
        '<input id="cbRpEmail" type="email" autocomplete="email" placeholder="you@example.com"/>' +
        '<label>新密码</label>' +
        pwdRow('cbRpPass', '6–32 位，数字或字母都行', 'new-password') +
        '<label>验证码（点「获取」后去邮箱收）</label>' +
        '<div class="cbcode">' +
          '<input id="cbRpCode" inputmode="numeric" autocomplete="one-time-code" placeholder="4–8 位验证码"/>' +
          '<button type="button" class="cbget" id="cbRpSend">获取验证码</button>' +
        '</div>' +
        '<div class="msg" id="cbRpMsg"></div>' +
        '<div class="row2"><button class="b1 cbfull" id="cbRpGo">设新密码并登录</button></div>' +
        '<div class="tiny cbfoot">想起来了？<a href="javascript:;" id="cbRpBack">返回登录</a></div>' +
      '</div>';

    area.innerHTML = PANE_LOGIN + PANE_REG + PANE_RESET;

    /* 密码明暗切换：整块面板重画后统一挂一遍，不必逐个输入框点名 */
    Array.prototype.forEach.call(area.querySelectorAll('.cb-eye'), function(b){
      b.onclick = function(){
        var i = area.querySelector('#' + b.getAttribute('data-eye'));
        if (!i) return;
        var show = i.type !== 'text';
        i.type = show ? 'text' : 'password';
        b.textContent = show ? '隐藏' : '显示';
      };
    });

    /* —— 单屏切换：只换三块面板的显隐，没有 Tab 条 —— */
    function showTab(which){
      var pL = area.querySelector('#cbPaneLogin'), pR = area.querySelector('#cbPaneReg'), pP = area.querySelector('#cbPaneReset');
      if (!pL || !pR || !pP) return;
      pL.classList.toggle('cbhide', which !== 'login');
      pR.classList.toggle('cbhide', which !== 'reg');
      pP.classList.toggle('cbhide', which !== 'reset');
      var f = area.querySelector(which === 'reg' ? '#cbEmail' : (which === 'reset' ? '#cbRpEmail' : '#cbUser'));
      if (f) setTimeout(function(){ try { f.focus(); } catch (e) {} }, 40);
    }
    var toReg = area.querySelector('#cbToReg');
    if (toReg) toReg.onclick = function(){ showTab('reg'); };
    var toLogin = area.querySelector('#cbToLogin');
    if (toLogin) toLogin.onclick = function(){ showTab('login'); };
    var toReset = area.querySelector('#cbToReset');
    if (toReset) toReset.onclick = function(){ showTab('reset'); };
    var rpBack = area.querySelector('#cbRpBack');
    if (rpBack) rpBack.onclick = function(){ showTab('login'); };

    /* —— 登录 —— */
    var doAuth = async function(){
      var idv = (area.querySelector('#cbUser').value || '').trim();
      var pass = area.querySelector('#cbPass').value;
      var m = area.querySelector('#cbMsg');
      if (!idv || !pass) { m.textContent = '请填写邮箱和密码'; paint(m, 'bad'); return; }
      var btn = area.querySelector('#cbLogin');
      if (btn.disabled) return;
      btn.disabled = true; btn.textContent = '登录中…';
      m.textContent = ''; paint(m, 'dim');
      try {
        var u2 = await signIn(idv, pass);
        lg('auth', '登录成功：' + userName(u2), 'ok');
        m.textContent = '';
        var doc = await peek().catch(function(){ return null; });
        draw(area, u2);
        if (!doc) { toast('登录成功——点「立即上传」把本机收获本存上云'); }
        else { toast('欢迎回来——云端有你的备份，可点「从云端恢复」'); }
      } catch (e) {
        var t = errText(e, 'login');
        m.textContent = t;
        paint(m, 'bad');
        btn.disabled = false; btn.textContent = '登录';
        lg('auth', '登录失败：' + t, 'fail');
      }
    };
    area.querySelector('#cbLogin').onclick = function(){ doAuth(); };
    /* 回车即登录/注册 —— 表单该有的行为，旧版没有 */
    ['#cbUser', '#cbPass'].forEach(function (sel) {
      var el = area.querySelector(sel);
      if (el) el.addEventListener('keydown', function (e) { if (e.key === 'Enter') doAuth(); });
    });

    /* —— 获取验证码 ——
       关键修复：发信要 5~6 秒，必须在「点击瞬间」就把按钮锁死。
       旧版是 await 之后才在 startCooldown 里禁用，中间这段真空期可以狂点。 */
    area.querySelector('#cbSendCode').onclick = async function(){
      var email = (area.querySelector('#cbEmail').value || '').trim();
      var pass = area.querySelector('#cbEmailPass').value;
      var m = area.querySelector('#cbRegMsg');
      var btn = area.querySelector('#cbSendCode');

      if (_sending) return;                                        /* 真·防重入 */
      if (!isEmail(email)) { m.textContent = '请先填写正确的邮箱地址'; paint(m, 'bad'); return; }
      if (!pwdOk(pass)) { m.textContent = '密码设 6–32 位（纯数字或纯字母都行）'; paint(m, 'bad'); return; }

      _sending = true;
      btn.disabled = true;                                          /* ← 立刻锁，不等 await */
      var _ori = btn.textContent;
      btn.textContent = '发送中…';
      m.textContent = '';
      paint(m, 'dim');

      try {
        _pendingVerify = await startSignUp(email, pass);
        _pendingEmail = email;
        lg('auth', '注册：验证码已发送 ' + email, 'ok');
        m.innerHTML = '验证码已发到 <b>' + escH(email) + '</b>，去邮箱看看（可能在垃圾箱）。';
        paint(m, 'dim');
        _sending = false;
        startCooldown(btn);                                         /* 交给冷却接管按钮文案 */
        var ic = area.querySelector('#cbCode');
        if (ic) ic.focus();
      } catch (e) {
        _sending = false;
        btn.disabled = false; btn.textContent = _ori;
        var t = errText(e, 'signup');
        if (t === 'PROVIDER_OFF') { m.innerHTML = providerOffHtml(); lg('auth', '注册失败：云端未开启邮箱登录', 'fail'); }
        else { m.textContent = t; lg('auth', '注册失败：' + t, 'fail'); }
        paint(m, 'bad');
      }
    };

    /* —— 完成注册 ——
       刷新后 _pendingVerify 会丢，但 messageId 存在 localStorage 里，
       交给 finishSignUp(null, ...) 走重建路径，而不是干巴巴报「请先获取验证码」。 */
    area.querySelector('#cbFinish').onclick = async function(){
      var m = area.querySelector('#cbRegMsg');
      var btn = area.querySelector('#cbFinish');
      var code = (area.querySelector('#cbCode').value || '').trim();
      var email = (area.querySelector('#cbEmail').value || '').trim();

      if (!regResumable()) { m.textContent = '请先点「获取验证码」'; paint(m, 'bad'); return; }
      /* 内存里有闭包时，邮箱必须一致（改了邮箱等于换了凭证）；
         走 messageId 重建时以持久化的邮箱为准，这里比对也能拦住误改。 */
      if (_pendingEmail && _pendingEmail !== email) { m.textContent = '邮箱已修改，请重新点「获取验证码」'; paint(m, 'bad'); return; }
      if (!code) { m.textContent = '请填写邮件里的验证码'; paint(m, 'bad'); return; }
      if (!/^\d{4,8}$/.test(code)) { m.textContent = '验证码是 4–8 位数字，请检查一下'; paint(m, 'bad'); return; }
      if (btn.disabled) return;

      btn.disabled = true; btn.textContent = '验证中…';
      m.textContent = ''; paint(m, 'dim');
      try {
        var res = await finishSignUp(_pendingVerify, code, '', email);
        lg('auth', '注册成功：' + (res.user ? userName(res.user) : email), 'ok');
        draw(area, res.user);
        toast('账号已创建——点「立即上传」把本机收获本存上云');
      } catch (e) {
        var t = errText(e, 'signup');
        if (t === 'PROVIDER_OFF') { m.innerHTML = providerOffHtml(); }
        else { m.textContent = t; }
        paint(m, 'bad');
        btn.disabled = false; btn.textContent = '完成注册';
        lg('auth', '注册失败：' + (t === 'PROVIDER_OFF' ? '云端未开启邮箱登录' : t), 'fail');
      }
    };

    /* —— 重设密码：先拿验证码（发信慢，点瞬间锁按钮，和注册同一套规矩）—— */
    var _rpUpdate = null, _rpMail = '';
    area.querySelector('#cbRpSend').onclick = async function(){
      var email = (area.querySelector('#cbRpEmail').value || '').trim();
      var m = area.querySelector('#cbRpMsg');
      var btn = area.querySelector('#cbRpSend');
      if (_sending) return;
      if (!isEmail(email)) { m.textContent = '请先填写正确的邮箱地址'; paint(m, 'bad'); return; }
      _sending = true;
      btn.disabled = true;
      var _ori = btn.textContent;
      btn.textContent = '发送中…';
      m.textContent = ''; paint(m, 'dim');
      try {
        _rpUpdate = await startReset(email);
        _rpMail = email;
        lg('auth', '重设密码：验证码已发送 ' + email, 'ok');
        m.innerHTML = '验证码已发到 <b>' + escH(email) + '</b>，去邮箱看看（可能在垃圾箱）。';
        paint(m, 'dim');
        _sending = false;
        startCooldown(btn);
        var ic = area.querySelector('#cbRpCode');
        if (ic) ic.focus();
      } catch (e) {
        _sending = false;
        btn.disabled = false; btn.textContent = _ori;
        var t = errText(e, 'reset');
        if (t === 'PROVIDER_OFF') m.innerHTML = providerOffHtml();
        else m.textContent = t;
        paint(m, 'bad');
        lg('auth', '重设密码失败：' + t, 'fail');
      }
    };
    area.querySelector('#cbRpGo').onclick = async function(){
      var m = area.querySelector('#cbRpMsg');
      var btn = area.querySelector('#cbRpGo');
      var email = (area.querySelector('#cbRpEmail').value || '').trim();
      var code = (area.querySelector('#cbRpCode').value || '').trim();
      var pass = area.querySelector('#cbRpPass').value;
      if (!_rpUpdate) { m.textContent = '先点「获取验证码」'; paint(m, 'bad'); return; }
      if (_rpMail && _rpMail !== email) { m.textContent = '邮箱已修改，请重新点「获取验证码」'; paint(m, 'bad'); return; }
      if (!/^\d{4,8}$/.test(code)) { m.textContent = '验证码是 4–8 位数字，请检查一下'; paint(m, 'bad'); return; }
      if (!pwdOk(pass)) { m.textContent = '新密码设 6–32 位（纯数字或纯字母都行）'; paint(m, 'bad'); return; }
      if (btn.disabled) return;
      btn.disabled = true; btn.textContent = '提交中…';
      m.textContent = ''; paint(m, 'dim');
      try {
        var u = await finishReset(_rpUpdate, code, pass);
        _rpUpdate = null;
        lg('auth', '密码已重设：' + userName(u), 'ok');
        draw(area, u);
        toast('密码已重设，这台设备已经登录上了');
      } catch (e) {
        var t2 = errText(e, 'reset');
        m.textContent = t2; paint(m, 'bad');
        btn.disabled = false; btn.textContent = '设新密码并登录';
        lg('auth', '重设密码失败：' + t2, 'fail');
      }
    };

    /* —— 开场定位 ——
       如果上次发过验证码还没完成注册（刷新过），直接把用户放回注册页并提示，
       而不是让他在「登录」tab 里对着空白发愣。 */
    var pend = loadRegPending();
    if (pend && pend.messageId) {
      showTab('reg');
      var pe = area.querySelector('#cbEmail');
      if (pe && pend.email) pe.value = pend.email;
      var pn = area.querySelector('#cbRegNotice');
      if (pn) {
        pn.classList.remove('cbhide');
        pn.innerHTML = '<b>还差一步</b>：验证码已发到 ' + escH(pend.email) + '，填进下面第 2 步就能完成注册。';
      }
      var sc = area.querySelector('#cbSendCode');
      if (sc) { sc.textContent = '重新获取'; sc.disabled = false; }
    }
  }

  /* v2.10.0 新增：给 index.html 的「账号」面板用。
     isOn()    —— 是否「已登录且曾经同步过」（即自动上传会生效）。
                  注意：v2.9.x 的 index.html 曾在 try/catch 里调用 CBSync.isOn()，
                  但本文件当时并未导出它，导致状态条永远显示「云同步未开」。
                  这里补上导出，修掉那个静默失效。
     current() —— 同步取当前登录用户（可能为 null），供面板顶部显示「已登录：xxx」。 */
  function isOn(){ return !!lastInfo(); }
  function current(){ return _userCache; }

  /* v2.15.0 反馈通道：网友写给作者的提醒，落 release_feedback 集合。
     规则设计成「登录者可写、谁都读不到」——内容只有作者在云控制台能看，网友之间互不可见。 */
  var FB_COLL = 'release_feedback';
  async function feedback(text){
    text = String(text || '').trim().slice(0, 500);
    if (!text) throw new Error('先写一句要说的');
    var u = await sess();
    if (!u) throw new Error('先在收获本页登录，反馈才能送到作者手里');
    var uid = uidOf(u); if (!uid) throw new Error('登录态缺少 uid');
    await db.collection(FB_COLL).add({
      ownerId: uid, text: text, at: new Date().toISOString(),
      ua: (navigator.userAgent || '').slice(0, 120)
    });
  }

  /* v2.13.0 开机云合并：页面装载后查一次云端。
     另一台设备有更新 → 合并进本机并刷新（sessionStorage 守卫保证每会话只判一次，不会刷新循环）。 */
  async function startupMerge(){
    try {
      if (!boot()) return null;
      var u = await sess(); if (!u) return null;
      var doc = await remoteAhead(); if (!doc) return null;
      var st = applyCloudMerge(doc.payload);
      try { localStorage.setItem(LS_LAST, JSON.stringify({ at: doc.updatedAt })); } catch (e) {}
      if (st.changedAny) {
        lg('cloud', '开机合并云端更新：+' + st.onlyCloud + ' 部自云端、' + st.merged + ' 部并集、-' + st.dropped + ' 部已删，刷新生效', 'ok');
        location.reload();
      }
      if (st.lookChanged && window.__atLook) { try { window.__atLook(); } catch (e) {} }
      return st;
    } catch (e) { lg('cloud', '开机云合并跳过（' + ((e && e.message) || '未知') + '）', 'warn'); return null; }
  }
  try {
    window.addEventListener('load', function(){
      if (sessionStorage.getItem('at_cb_boot_done')) return;
      sessionStorage.setItem('at_cb_boot_done', '1');
      setTimeout(startupMerge, 1200);
    });
  } catch (e) {}

  window.CBSync = { mount: mount, autosync: autosync, get version(){ return window.AT_VERSION || VERSION; }, isOn: isOn, current: current, startupMerge: startupMerge, applyCloudMerge: applyCloudMerge, feedback: feedback };
})();
