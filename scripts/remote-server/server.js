/**
 * 装维工具箱 · 远程 AI 控制 —— WebSocket 中转服务（服务器端）
 *
 * 职责：只做「注册、账号校验、归属配对、转发、保活」，消息不落盘（转发即弃）。
 * 协议见 REMOTE_CONTROL_DESIGN.md 第 2 节，JSON 文本帧。
 *
 * 账号体系（用户拍板）：
 * - 账号存同目录 users.json（无则自动创建），结构 [{name, salt, pwdHash}]；
 * - 密码哈希 = sha256(密码 + 盐)，盐为随机 hex 存字段；
 * - 控制端「注册账号 → 登录」；设备 register 帧带账号密码 → 校验通过后设备挂到该账号名下；
 * - 控制端只看自己名下的设备，绑定校验设备归属；无账号的旧设备列表可见但绑定被拒。
 *
 * 运行：node server.js  （监听 8091 端口，WebSocket 路径 /ws）
 * 依赖：仅 ws 库（WebSocket 协议实现），Node 20+。
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

// ============ 常量 ============
const PORT = 8091;                         // 监听端口（宝塔「安全」需放行）
const WS_PATH = '/ws';                     // WebSocket 路径（与设计文档一致）
const USERS_FILE = path.join(__dirname, 'users.json'); // 账号存储文件（同目录）
const HEARTBEAT_INTERVAL_MS = 15 * 1000;   // 心跳间隔：每 15 秒给设备发 ping
const OFFLINE_TIMEOUT_MS = 90 * 1000;      // 离线判定：90 秒没收到 pong 判离线
const MAX_FRAME_BYTES = 512 * 1024;        // 单帧上限 512KB（历史回放含工具输出较大；仍限长防滥用）
const READY_OPEN = 1;                      // WebSocket readyState 常量：1 = OPEN

// ============ 防滥用防护常量（2026-08-16 用户要求补充） ============
const LOGIN_LOCK_THRESHOLD = 5;            // 同账号登录失败次数阈值
const LOGIN_LOCK_MS = 15 * 60 * 1000;      // 锁定 15 分钟后自动解除
const RATE_LIMIT_MAX = 10;                 // 每 IP 每分钟最多注册/登录尝试次数
const RATE_LIMIT_WINDOW_MS = 60 * 1000;    // 频控窗口 1 分钟
const ACCOUNT_NAME_MIN = 2;                // 账号名长度下限（2-20 字符）
const ACCOUNT_NAME_MAX = 20;               // 账号名长度上限
const PWD_MIN = 6;                         // 密码最短 6 位
const TOKEN_TTL_MS = 30 * 24 * 3600 * 1000; // 登录令牌有效期 30 天（内存态，服务重启失效）

// ============ 账号存储（users.json，无则创建） ============
// 结构：[{ name, salt, pwdHash }]，盐为随机 hex 字符串，pwdHash = sha256(密码 + 盐) hex
let users = [];

/** 从 users.json 加载账号（文件不存在/损坏则从空表开始） */
function loadUsers() {
  try {
    const raw = fs.readFileSync(USERS_FILE, 'utf8');
    const arr = JSON.parse(raw);
    if (Array.isArray(arr)) users = arr;
  } catch (err) {
    users = []; // 首次启动：无文件，空账号表
  }
}

/** 账号表落盘（写入失败仅记日志，不影响运行） */
function saveUsers() {
  try {
    fs.writeFileSync(USERS_FILE, JSON.stringify(users, null, 2));
  } catch (err) {
    console.log('[srv] save users FAIL');
  }
}

/** 密码哈希：sha256(密码 + 盐) 十六进制 */
function hashPwd(pwd, salt) {
  return crypto.createHash('sha256').update(pwd + salt, 'utf8').digest('hex');
}

/** 按账号名查用户（不存在返回 null） */
function findUser(name) {
  for (const u of users) {
    if (u.name === name) return u;
  }
  return null;
}

// ============ 内存状态（进程存活期有效，不落盘） ============
// 设备表：deviceId -> { ws, name, model, version, account, status, lastPong, online, currentRoundId }
const devices = new Map();
// 反向索引：设备 socket -> deviceId（用于消息/断开时反查设备）
const deviceWsMap = new Map();
// 控制端表：controlWs -> { loggedIn, account, boundDeviceId }
const controls = new Map();

// roundId 生成：时间戳 + 自增计数（保证唯一、单调递增）
let roundCounter = 0;
// 成败计数（仅统计，打 stdout）
let loginFailCount = 0;               // 登录失败累计次数
let registerAccountFailCount = 0;     // 注册账号失败累计次数

// 登录失败锁定表：账号名 -> { count, lockUntil }（内存态，进程重启清零）
const loginLocks = new Map();
// IP 频控表：来源 IP -> 尝试时间戳数组（注册/登录共用，内存态）
const rateLimits = new Map();
// 登录令牌表：token -> { name, expiresAt }（登录成功签发；控制端存 localStorage/本地，
// 刷新页面免重输密码；30 天过期，服务重启失效需重新登录）
const loginTokens = new Map();

/** 生成登录令牌（随机 32 hex） */
function genToken() {
  return crypto.randomBytes(16).toString('hex');
}

/**
 * 取连接来源 IP（仅用于频控/审计计数；x-forwarded-for 首段或 socket 远端地址）
 */
function clientIp(req) {
  const xff = req.headers && req.headers['x-forwarded-for'];
  if (typeof xff === 'string' && xff.length > 0) return xff.split(',')[0].trim();
  return (req.socket && req.socket.remoteAddress) || '';
}

/**
 * 频控检查（防暴力注册/登录）：窗口内尝试次数超限返回 true（本次拒绝），
 * 否则记录本次尝试并返回 false。
 */
function rateLimited(ip) {
  if (!ip) return false; // 拿不到 IP 时不拦（保可用性）
  const now = Date.now();
  const arr = (rateLimits.get(ip) || []).filter((t) => now - t < RATE_LIMIT_WINDOW_MS);
  if (arr.length >= RATE_LIMIT_MAX) {
    rateLimits.set(ip, arr);
    return true;
  }
  arr.push(now);
  rateLimits.set(ip, arr);
  return false;
}

// ============ 工具函数 ============

/** 生成新一轮 roundId */
function nextRoundId() {
  roundCounter += 1;
  return 'r' + Date.now().toString(36) + '-' + roundCounter;
}

/** 服务器 → 设备 安全发送（连接可能已断，全部 try/catch） */
function safeSendDevice(ws, obj) {
  try {
    if (ws && ws.readyState === READY_OPEN) {
      ws.send(JSON.stringify(obj));
    }
  } catch (err) {
    // 连接已断：静默忽略，不打内容日志
  }
}

/** 服务器 → 控制端 安全发送（连接可能已断，全部 try/catch） */
function safeSendControl(ws, obj) {
  try {
    if (ws && ws.readyState === READY_OPEN) {
      ws.send(JSON.stringify(obj));
    }
  } catch (err) {
    // 连接已断：静默忽略，不打内容日志
  }
}

/** 汇总设备列表（按登录账号过滤：仅自己名下 + 无账号旧设备可见） */
function buildDeviceList(accountName) {
  const list = [];
  for (const [deviceId, device] of devices) {
    // 归属过滤：设备已绑定账号时只给该账号看；未绑定账号（旧设备）对所有人可见（bind 时再拒绝）
    if (device.account && device.account !== accountName) continue;
    list.push({
      deviceId,
      name: device.name,
      model: device.model,
      online: device.online,
      account: device.account || '',   // 归属账号名（旧设备为空串，控制页显示「未绑定」）
      status: device.status
    });
  }
  return list;
}

/** 向所有已登录控制端广播设备列表（各自按账号过滤，设备增删/状态变化时调用） */
function broadcastDeviceList() {
  for (const [controlWs, control] of controls) {
    if (control.loggedIn) {
      safeSendControl(controlWs, { t: 'device_list', devices: buildDeviceList(control.account) });
    }
  }
}

/** 向单个控制端下发设备列表（登录成功后调用，按该账号过滤） */
function sendDeviceListTo(controlWs) {
  const control = controls.get(controlWs);
  if (!control || !control.loggedIn) return;
  safeSendControl(controlWs, { t: 'device_list', devices: buildDeviceList(control.account) });
}

/** 把设备帧原样转发给绑定该设备的控制端（ai_event / ai_done / approval_req） */
function forwardToBoundControl(deviceId, msg) {
  for (const [controlWs, control] of controls) {
    if (control.boundDeviceId === deviceId) {
      safeSendControl(controlWs, msg);
    }
  }
}

/** 设备离线：通知绑定它的控制端，并解除其绑定 */
function notifyBoundOffline(deviceId) {
  for (const [controlWs, control] of controls) {
    if (control.boundDeviceId === deviceId) {
      safeSendControl(controlWs, { t: 'offline', deviceId });
      control.boundDeviceId = null;
    }
  }
}

/** 标记设备离线并广播（心跳超时 / 连接断开共用） */
function markDeviceOffline(deviceId, reason) {
  const device = devices.get(deviceId);
  if (!device || !device.online) return;
  device.online = false;
  console.log(`[dev] offline deviceId=${deviceId} reason=${reason}`);
  notifyBoundOffline(deviceId);
  broadcastDeviceList();
}

// ============ 消息处理 ============

/** 设备注册（PDA 上线，可带 account/pwd 归属账号） */
function handleRegister(ws, msg) {
  // 一个 socket 不能既是设备又是控制端
  if (controls.has(ws)) return;
  const deviceId = msg.deviceId;
  if (!deviceId || typeof deviceId !== 'string' || deviceId.length === 0) return;

  // 账号校验：register 帧带 account 字段则校验账号存在 + 密码正确，通过后设备挂到该账号名下；
  // 旧设备（无 account 字段 / 空串）跳过校验，列表可见但 bind 拒绝。
  let accountName = null;
  if (typeof msg.account === 'string' && msg.account.length > 0) {
    const user = findUser(msg.account);
    const pwd = typeof msg.pwd === 'string' ? msg.pwd : '';
    if (!user || hashPwd(pwd, user.salt) !== user.pwdHash) {
      // 账号不存在或密码错误：回 register_fail 并断开（设备端需提示）
      safeSendDevice(ws, { t: 'register_fail', reason: '账号或密码错误' });
      console.log(`[dev] register FAIL deviceId=${deviceId} reason=bad-account`);
      try { ws.close(); } catch (err) { /* 忽略 */ }
      return;
    }
    accountName = msg.account;
  }

  const now = Date.now();
  const existing = devices.get(deviceId);

  if (existing) {
    // 重连：复用原条目，刷新 socket / 心跳 / 上线状态 / 归属账号
    const oldWs = existing.ws;
    // 先清掉旧 socket 反向映射，避免旧连接断开时误判离线
    for (const [w, id] of deviceWsMap) {
      if (id === deviceId && w !== ws) deviceWsMap.delete(w);
    }
    existing.ws = ws;
    existing.lastPong = now;
    existing.online = true;
    existing.account = accountName;
    if (typeof msg.name === 'string') existing.name = msg.name;
    if (typeof msg.model === 'string') existing.model = msg.model;
    if (typeof msg.version !== 'undefined') existing.version = msg.version;
    // 主动关闭旧连接（若仍存活），避免残留
    if (oldWs && oldWs !== ws) {
      try { oldWs.close(); } catch (err) { /* 忽略 */ }
    }
  } else {
    devices.set(deviceId, {
      ws,
      name: typeof msg.name === 'string' ? msg.name : deviceId,
      model: typeof msg.model === 'string' ? msg.model : '',
      version: typeof msg.version !== 'undefined' ? msg.version : '',
      account: accountName, // 归属账号（旧设备为 null）
      status: null,         // 初始状态为空，等 status 帧更新
      lastPong: now,
      online: true,
      currentRoundId: null
    });
  }
  deviceWsMap.set(ws, deviceId);

  // 兼容：register 里若带初始电量/SSID/busy，一并作为初始状态
  const entry = devices.get(deviceId);
  if (msg.battery !== undefined || msg.ssid !== undefined || msg.busy !== undefined) {
    entry.status = { battery: msg.battery, ssid: msg.ssid, busy: msg.busy };
  }

  console.log(`[dev] register deviceId=${deviceId} name=${entry.name} account=${entry.account || '-'}`);
  broadcastDeviceList();
}

/** 控制端注册账号（装维名 + 密码） */
function handleRegisterAccount(ws, msg) {
  if (deviceWsMap.has(ws)) return;  // 设备 socket 不能注册账号
  if (controls.has(ws)) return;     // 已登录忽略

  // IP 频控：每分钟最多 RATE_LIMIT_MAX 次注册/登录尝试（防刷账号/暴力）
  if (rateLimited(ws._ip)) {
    safeSendControl(ws, { t: 'register_fail', reason: '操作过于频繁，请稍后再试' });
    console.log(`[ctl] register_account RATE_LIMITED ip=${ws._ip}`);
    return;
  }

  const name = typeof msg.name === 'string' ? msg.name.trim() : '';
  const pwd = typeof msg.pwd === 'string' ? msg.pwd : '';
  // 参数校验：账号名 2-20 字符、密码至少 6 位
  if (name.length < ACCOUNT_NAME_MIN || name.length > ACCOUNT_NAME_MAX) {
    safeSendControl(ws, { t: 'register_fail', reason: `账号名需 ${ACCOUNT_NAME_MIN}-${ACCOUNT_NAME_MAX} 个字符` });
    registerAccountFailCount += 1;
    console.log(`[ctl] register_account FAIL reason=bad-name (failCount=${registerAccountFailCount})`);
    return;
  }
  if (pwd.length < PWD_MIN) {
    safeSendControl(ws, { t: 'register_fail', reason: `密码至少 ${PWD_MIN} 位` });
    registerAccountFailCount += 1;
    console.log(`[ctl] register_account FAIL reason=weak-pwd (failCount=${registerAccountFailCount})`);
    return;
  }
  // 账号已存在：模糊提示（不泄露"账号已被占用"可枚举信息）
  if (findUser(name)) {
    safeSendControl(ws, { t: 'register_fail', reason: '注册失败，请换一个账号名' });
    registerAccountFailCount += 1;
    console.log(`[ctl] register_account FAIL reason=exists (failCount=${registerAccountFailCount})`);
    return;
  }
  // 创建账号：随机盐 + sha256(密码+盐) 落盘
  const salt = crypto.randomBytes(16).toString('hex');
  users.push({ name, salt, pwdHash: hashPwd(pwd, salt) });
  saveUsers();
  safeSendControl(ws, { t: 'register_ok' });
  console.log(`[ctl] register_account ok name=${name}`);
}

/** 完成登录（密码校验通过或 token 有效后调用）：踢旧会话 + 建会话 + 回 login_ok/device_list */
function finishLogin(ws, name, freshToken) {
  // 单点登录：同一账号只允许一个控制端在线——后登录者顶掉先登录者
  for (const [oldWs, old] of controls) {
    if (old.loggedIn && old.account === name && oldWs !== ws) {
      safeSendControl(oldWs, { t: 'kicked_login', reason: '该账号已在其他位置登录，本连接已被顶下线' });
      console.log(`[ctl] kick old session name=${name}`);
      try { oldWs.close(); } catch (err) { /* 忽略 */ }
      controls.delete(oldWs);
    }
  }
  controls.set(ws, { loggedIn: true, account: name, boundDeviceId: null });
  console.log(`[ctl] login ok name=${name}`);
  if (freshToken) safeSendControl(ws, { t: 'login_ok', token: freshToken, account: name });
  sendDeviceListTo(ws);
}

/** 控制端登录（账号+密码，或已签发 token 免密登录；旧协议只传 pwd 视为失败） */
function handleLogin(ws, msg) {
  if (deviceWsMap.has(ws)) return;   // 设备 socket 不能作为控制端
  if (controls.has(ws)) return;      // 已登录，忽略重复

  // token 免密登录：校验有效即完成登录（不参与频控/锁定）
  const token = typeof msg.token === 'string' ? msg.token.trim() : '';
  if (token) {
    const rec = loginTokens.get(token);
    if (!rec || rec.expiresAt < Date.now()) {
      if (rec) loginTokens.delete(token);
      safeSendControl(ws, { t: 'login_fail', reason: '登录已过期，请重新登录' });
      console.log(`[ctl] login TOKEN_INVALID`);
      try { ws.close(); } catch (err) { /* 忽略 */ }
      return;
    }
    finishLogin(ws, rec.name, null); // 沿用旧 token，不回新 token
    return;
  }

  // IP 频控：每分钟最多 RATE_LIMIT_MAX 次注册/登录尝试（防暴力）
  if (rateLimited(ws._ip)) {
    safeSendControl(ws, { t: 'login_fail', reason: '操作过于频繁，请稍后再试' });
    console.log(`[ctl] login RATE_LIMITED ip=${ws._ip}`);
    try { ws.close(); } catch (err) { /* 忽略 */ }
    return;
  }

  const name = typeof msg.name === 'string' ? msg.name.trim() : '';
  const pwd = typeof msg.pwd === 'string' ? msg.pwd : '';
  // 失败锁定：同账号连续错 5 次锁 15 分钟（自动解除）
  const lock = loginLocks.get(name);
  if (lock && lock.lockUntil > Date.now()) {
    loginFailCount += 1;
    console.log(`[ctl] login LOCKED name=${name}`);
    safeSendControl(ws, { t: 'login_fail', reason: '失败次数过多，账号已锁定，请 15 分钟后再试' });
    try { ws.close(); } catch (err) { /* 忽略 */ }
    return;
  }
  const user = name ? findUser(name) : null;
  if (!user || hashPwd(pwd, user.salt) !== user.pwdHash) {
    // 失败计数与锁定
    const rec = loginLocks.get(name) || { count: 0, lockUntil: 0 };
    rec.count += 1;
    if (rec.count >= LOGIN_LOCK_THRESHOLD) {
      rec.lockUntil = Date.now() + LOGIN_LOCK_MS;
      rec.count = 0;
      console.log(`[ctl] login LOCKING name=${name} for ${LOGIN_LOCK_MS / 60000}min`);
    }
    loginLocks.set(name, rec);
    loginFailCount += 1;
    console.log(`[ctl] login FAIL name=${name || '(empty)'} (failCount=${loginFailCount})`);
    // 回 login_fail 给控制端显示原因，再断开
    safeSendControl(ws, { t: 'login_fail', reason: '账号或密码错误' });
    try { ws.close(); } catch (err) { /* 忽略 */ }
    return;
  }
  loginLocks.delete(name); // 登录成功清零失败计数
  const freshToken = genToken();
  loginTokens.set(freshToken, { name, expiresAt: Date.now() + TOKEN_TTL_MS });
  finishLogin(ws, name, freshToken);
}

/** 设备帧分发（status / pong / ai_event / ai_done / approval_req） */
function handleDeviceMessage(ws, msg) {
  const deviceId = deviceWsMap.get(ws);
  if (!deviceId) return;
  const device = devices.get(deviceId);
  if (!device) return;
  if (device.ws !== ws) return; // 旧 socket 的残留消息，忽略

  switch (msg.t) {
    case 'status':
      device.status = {
        battery: msg.battery,
        ssid: msg.ssid,
        busy: msg.busy
      };
      device.lastPong = Date.now(); // 状态帧同样视作心跳
      if (!device.online) device.online = true;
      broadcastDeviceList(); // 状态变化推送 device_list
      break;

    case 'pong':
      device.lastPong = Date.now();
      if (!device.online) {
        device.online = true;
        broadcastDeviceList();
      }
      break;

    case 'ai_event':
    case 'ai_done':
    case 'approval_req':
    case 'session_list_resp':
    case 'session_switched':
    case 'session_err':
    case 'memory_list_resp':
    case 'round_deleted':
      // 原样转发给绑定该设备的控制端
      forwardToBoundControl(deviceId, msg);
      break;

    default:
      break; // 未知帧忽略
  }
}

/** 控制端绑定设备（含归属校验） */
function handleBind(ws, control, msg) {
  const deviceId = msg.deviceId;
  if (!deviceId || typeof deviceId !== 'string') return;
  const device = devices.get(deviceId);

  // 设备不存在或已离线：直接回 offline，不建立绑定
  if (!device || !device.online) {
    safeSendControl(ws, { t: 'offline', deviceId });
    return;
  }

  // 归属校验：设备必须属于当前登录账号
  if (!device.account) {
    // 旧设备未绑定账号：列表可见但拒绝绑定，提示去 App 填账号后重启远程
    safeSendControl(ws, { t: 'bind_fail', reason: '设备未绑定账号，请在 App 远程设置里填写账号后重启远程' });
    return;
  }
  if (device.account !== control.account) {
    safeSendControl(ws, { t: 'bind_fail', reason: '该设备不属于你的账号' });
    return;
  }

  // 同一设备后到者顶掉先到者：踢掉其它已绑定该设备的控制端
  for (const [otherWs, other] of controls) {
    if (otherWs !== ws && other.boundDeviceId === deviceId) {
      safeSendControl(otherWs, { t: 'kicked' });
      other.boundDeviceId = null;
    }
  }

  control.boundDeviceId = deviceId;
  safeSendControl(ws, { t: 'bound', deviceId });
}

/** 控制端发 AI 消息 → 转发设备 ai_chat（roundId 服务端生成） */
function handleChat(ws, control, msg) {
  if (!control.boundDeviceId) return;
  const device = devices.get(control.boundDeviceId);
  if (!device || !device.online) return;

  const roundId = nextRoundId();
  device.currentRoundId = roundId; // 记录进行中的 roundId，供 stop 使用
  safeSendDevice(device.ws, {
    t: 'ai_chat',
    roundId,
    text: typeof msg.text === 'string' ? msg.text : '',
    authorized: !!msg.authorized
  });
}

/** 控制端停止 → 转发设备 ai_stop（roundId 用当前进行中的 roundId） */
function handleStop(ws, control, msg) {
  if (!control.boundDeviceId) return;
  const device = devices.get(control.boundDeviceId);
  if (!device || !device.online) return;
  if (!device.currentRoundId) return; // 没有进行中的轮次，忽略

  safeSendDevice(device.ws, { t: 'ai_stop', roundId: device.currentRoundId });
}

/** 控制端审批响应 → 转发设备 approval_resp */
function handleApprove(ws, control, msg) {
  if (!control.boundDeviceId) return;
  const device = devices.get(control.boundDeviceId);
  if (!device || !device.online) return;

  safeSendDevice(device.ws, {
    t: 'approval_resp',
    reqId: msg.reqId,
    allow: !!msg.allow
  });
}

/** 控制端会话操作 → 原样转发设备（session_* 帧无 roundId，字段原样带过去） */
function handleSessionControl(ws, control, msg) {
  if (!control.boundDeviceId) return;
  const device = devices.get(control.boundDeviceId);
  if (!device || !device.online) return;
  safeSendDevice(device.ws, msg);
}

/** 控制端帧分发（bind / chat / stop / approve / session_*） */
function handleControlMessage(ws, msg) {
  const control = controls.get(ws);
  if (!control || !control.loggedIn) return; // 未登录不发任何数据

  switch (msg.t) {
    case 'bind': handleBind(ws, control, msg); break;
    case 'chat': handleChat(ws, control, msg); break;
    case 'stop': handleStop(ws, control, msg); break;
    case 'approve': handleApprove(ws, control, msg); break;
    case 'logout': {
      // 控制端退出登录：吊销本地令牌（下次需账号密码重新登录）
      const t = typeof msg.token === 'string' ? msg.token.trim() : '';
      if (t) loginTokens.delete(t);
      break;
    }
    case 'session_list':
    case 'session_switch':
    case 'session_new':
    case 'session_clear':
    case 'session_delete':
    case 'session_rename':
    case 'memory_list':
    case 'memory_delete':
    case 'memory_clear':
    case 'round_delete':
      handleSessionControl(ws, control, msg);
      break;
    default: break; // 未知帧忽略
  }
}

/** 顶层消息分发（按 t 路由到设备/控制端处理器） */
function handleMessage(ws, msg) {
  switch (msg.t) {
    case 'register':
      handleRegister(ws, msg);
      break;
    case 'register_account':
      handleRegisterAccount(ws, msg);
      break;
    case 'login':
      handleLogin(ws, msg);
      break;
    case 'status':
    case 'pong':
    case 'ai_event':
    case 'ai_done':
    case 'approval_req':
    case 'session_list_resp':
    case 'session_switched':
    case 'session_err':
    case 'memory_list_resp':
    case 'round_deleted':
      handleDeviceMessage(ws, msg);
      break;
    case 'bind':
    case 'chat':
    case 'stop':
    case 'approve':
    case 'logout':
    case 'session_list':
    case 'session_switch':
    case 'session_new':
    case 'session_clear':
    case 'session_delete':
    case 'session_rename':
    case 'memory_list':
    case 'memory_delete':
    case 'memory_clear':
    case 'round_delete':
      handleControlMessage(ws, msg);
      break;
    default:
      break; // 未知类型忽略
  }
}

// ============ HTTP + WebSocket 服务 ============

// 简单 HTTP 响应：供端口健康探测使用（如 curl http://host:8091/）
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('toolbox-remote ok');
});

// WebSocket 服务：限定 /ws 路径，单帧上限 64KB（超长由 ws 库直接拒绝）
const wss = new WebSocketServer({
  server,
  path: WS_PATH,
  maxPayload: MAX_FRAME_BYTES
});

wss.on('connection', (ws, req) => {
  // 连接建立后不做任何主动下发，等 register/login/register_account 判定角色
  ws._ip = clientIp(req); // 记录来源 IP（频控用）

  ws.on('message', (data, isBinary) => {
    if (isBinary) return;                       // 协议为 JSON 文本帧，二进制帧忽略
    if (data.length > MAX_FRAME_BYTES) return;  // 超长帧丢弃（maxPayload 已兜底，此处双保险）

    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch (err) {
      return; // 非 JSON 帧忽略
    }
    if (!msg || typeof msg !== 'object' || typeof msg.t !== 'string') return;

    handleMessage(ws, msg);
  });

  ws.on('close', () => {
    // 设备断开：标记离线并广播
    const deviceId = deviceWsMap.get(ws);
    if (deviceId) {
      const device = devices.get(deviceId);
      if (device && device.ws === ws) {
        markDeviceOffline(deviceId, 'disconnect');
      }
      deviceWsMap.delete(ws);
    }
    // 控制端断开：从控制端表移除
    if (controls.has(ws)) {
      controls.delete(ws);
      console.log('[ctl] disconnect');
    }
  });

  ws.on('error', () => {
    // 连接错误（如超长帧被 maxPayload 拒绝）静默处理，不打内容日志
  });
});

// ============ 心跳：每 15 秒给所有设备发 ping，并检查 90 秒超时 ============
setInterval(() => {
  const now = Date.now();
  for (const [deviceId, device] of devices) {
    // 给设备发 ping（离线但 socket 仍活着的设备也发，便于其恢复）
    safeSendDevice(device.ws, { t: 'ping' });
    // 超时判离线
    if (device.online && now - device.lastPong > OFFLINE_TIMEOUT_MS) {
      markDeviceOffline(deviceId, 'heartbeat-timeout');
    }
  }
}, HEARTBEAT_INTERVAL_MS);

// ============ 启动 ============
loadUsers(); // 启动时加载账号表
server.listen(PORT, () => {
  console.log(`[srv] toolbox-remote listening on :${PORT} (ws path=${WS_PATH}, users=${users.length})`);
});

// ============ 优雅退出（Docker stop 时触发） ============
function shutdown() {
  console.log('[srv] shutting down');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

