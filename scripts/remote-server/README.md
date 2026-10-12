# 装维工具箱 · 远程 AI 控制 —— WebSocket 中转服务（M1 服务器端）

> 88 服务器上的 Node.js WebSocket 中转服务：只做**注册、配对、转发、保活**，消息不落盘（转发即弃）。
> 协议为 JSON 文本帧（注册/配对/会话同步/AI 轮次/记忆与审批/强制升级等消息类型），消息分发逻辑见 `server.js`。

## 文件清单

| 文件 | 说明 |
|---|---|
| `server.js` | 服务入口，零业务依赖（仅 `ws` 库），全部中文注释 |
| `package.json` | 依赖清单（仅 `ws`），`npm install --omit=dev` 即可 |
| `Dockerfile` | 自建镜像用（可选；宝塔部署也可直接挂目录跑） |
| `README.md` | 本说明 |

## 依赖

- Node.js 20+（镜像 `node:20-alpine`）
- `ws`（^8.18.0）—— WebSocket 协议实现，唯一的 npm 依赖

## 协议覆盖

| 方向 | 帧 | 处理 |
|---|---|---|
| 设备 → 服务 | `register` | 注册/重连，带 `account`/`pwd` 校验归属账号，记录 name/model/version，广播 device_list |
| 设备 → 服务 | `status` | 更新电量/SSID/busy，广播 device_list |
| 设备 → 服务 | `pong` | 刷新心跳时间戳 |
| 设备 → 服务 | `ai_event` / `ai_done` / `approval_req` | 原样转发给绑定该设备的控制端 |
| 控制端 → 服务 | `register_account` | 注册装维账号（`name`+`pwd`），回 `register_ok` / `register_fail` |
| 控制端 → 服务 | `login` | 校验账号密码（`name`+`pwd`），失败回 `login_fail` 并断开 |
| 控制端 → 服务 | `bind` | 绑定设备（校验归属：只可绑自己账号名下；后到者顶掉先到者） |
| 控制端 → 服务 | `chat` | 生成 roundId，转设备 `ai_chat` |
| 控制端 → 服务 | `stop` | 转设备 `ai_stop`（用进行中 roundId） |
| 控制端 → 服务 | `approve` | 转设备 `approval_resp` |
| 服务 → 控制端 | `device_list` / `bound` / `offline` / `kicked` / `bind_fail` / `register_ok` / `register_fail` / `login_fail` | 登录后、增删/状态变化、绑定、离线、被顶、绑定被拒、注册/登录结果时下发 |
| 服务 → 设备 | `ping` / `ai_chat` / `ai_stop` / `approval_resp` / `register_fail` | 心跳 / 转发 / 注册失败 |

账号体系：账号存同目录 `users.json`（`[{name, salt, pwdHash}]`，`pwdHash = sha256(密码+盐)`），控制端先「注册账号→登录」；`device_list` 只推登录账号名下设备（旧设备无账号对所有人可见但 `bind` 拒绝）。安全：单帧 ≤ 64KB 超长丢弃；非 JSON 帧忽略；控制端未登录不发任何数据；消息不落盘，stdout 只记连接/断开/注册/登录成败计数。

## 本地自检

```bash
cd scripts/remote-server
npm install --omit=dev --registry=https://registry.npmmirror.com
node --check server.js     # 语法自检
node server.js             # 启动，监听 8091
```

```bash
# 健康探测（另开终端）
curl http://127.0.0.1:8091/
# 期望输出：toolbox-remote ok
```

## 宝塔部署（必须走宝塔托管规范）

### ① 配置镜像加速

宝塔面板 → 「Docker」→「设置」→ 加速 URL 填：

```
https://docker.1ms.run
```

（面板此前已提示拉取异常，此加速站解决 `node:20-alpine` 拉取失败问题）

### ② 宿主机准备目录

SSH 登录 88 服务器，创建目录：

```bash
mkdir -p /www/dk_project/toolbox-remote
```

用宝塔「文件」面板或 SFTP，把本地 `scripts/remote-server/` 下的 `server.js` 与 `package.json` 上传到该目录。
（`Dockerfile`、`README.md` 可不上传，仅本地/自建镜像用）

### ③ 宝塔添加容器

宝塔面板 → 「Docker」→「容器」→「添加容器」，填写：

| 项 | 值 |
|---|---|
| 镜像 | `node:20-alpine` |
| 端口映射 | 宿主机 `8091` → 容器 `8091` |
| 目录映射 | 宿主机 `/www/dk_project/toolbox-remote` → 容器 `/app` |
| 命令 | `sh -c "cd /app && npm install --omit=dev --registry=https://registry.npmmirror.com && node server.js"` |
| 重启策略 | `always` |

说明：容器每次启动都在 `/app` 内安装依赖（走 npmmirror 镜像）后运行 `server.js`，宿主机目录改动只需重启容器即可生效。

### ④ 放行端口

宝塔面板 → 「安全」→ 放行端口 `8091`（TCP），备注「远程AI控制 WebSocket」。
（ufw 由宝塔管理，勿手动改）

### ⑤ 验证

```bash
# 宿主机/内网验证端口与 HTTP 健康探测
curl http://127.0.0.1:8091/
# 期望：toolbox-remote ok
```

Docker 日志（宝塔 Docker → 容器 → 日志）应能看到：

```
[srv] toolbox-remote listening on :8091 (ws path=/ws)
```

设备/控制页连接地址：`ws://your-server.example:8091/ws`（控制页在 8090 站点 `/toolbox/remote/`）。

## 安全与边界

- 服务器不落盘任何消息内容；stdout 只记连接/断开/注册/登录成败计数，不打消息正文。
- 账号体系：账号密码存 `users.json`（密码加盐哈希），不再用写死密码；控制端只能看到并绑定自己名下的设备。
- 单帧 ≤ 64KB 截断保护；单设备同一时刻只允许一个控制端绑定。
- v1 不做端到端加密（运维场景）。

