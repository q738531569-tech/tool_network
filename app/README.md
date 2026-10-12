# app — 应用壳模块

Compose 界面层与 App 级能力：三 tab 导航、全部功能页、OTA 自更新、远程控制服务。

## 导航与骨架

| 文件 | 职责 |
|---|---|
| `MainActivity` / `ui/MainScreen` | 入口与导航宿主（3 个顶级 tab + 二级功能页路由） |
| `ui/navigation/TopLevelDestination` | 路由常量表 + `HOME_SECONDARY` 首页二级入口注册（新增功能页需在此接线） |
| `ui/SplashScreen` | 开屏页 |
| `ui/theme/` | 品牌深蓝主题色 / 字体 / Material3 主题 |
| `ToolboxApp` | Application：全局单例（Room 数据库）+ 崩溃收集/使用统计/自动上报初始化 |

## 功能页（`ui/screens/`）

| 包 | 页面 |
|---|---|
| `home/` | 首页分类工作台（`ToolEntries.kt` 定义全部工具入口与大白话简介：网络工具/安全审计/安防设备） |
| `ai/` | AI 诊断助手（对话流式渲染、思考折叠、记忆/档案弹窗、root_shell 审批弹窗、会话管理） |
| `sos/` | SOS 紧急求救（一键求救/声光摩斯/短信模板编辑/紧急联系人） |
| `diagnose/` | 网络诊断（一键体检：通不通/哪里卡/端口开没开） |
| `discovery/` | 设备发现（扫描结果卡片、厂商识别、加入清单） |
| `capture/` | 抓包分析（时长可选、实时统计、风暴告警） |
| `wifi/` | Wi-Fi 分析 / 信道雷达 / 航空信号（DFS）检测 |
| `hik/` | 摄像头管理（发现列表、改 IP/密码、重启、恢复出厂、弱口令探测） |
| `tools/` | 14 个工具页：ARP 欺骗/网关冲突/环路检测、nmap 扫描、PCDN 检测、流量审计、静默断网、BLE 扫描、条码、子网计算、SNMP、WoL、弱网检测、远程终端 |
| `remote/` | 内置远程控制台（连接状态、审批三档） |
| `settings/` | 设置（API Key、模型下拉、服务器地址、远程开关、反馈与建议、诊断信息、使用说明、版本记录、OTA 检查） |

## App 级能力

| 包/文件 | 职责 |
|---|---|
| `ota/` | 自更新：`UpdateChecker`（GET `version.json`）→ `UpdateModels`（字段约定）→ `ApkUpdater`（下载 + FileProvider 拉起安装） |
| `remote/RemoteControlService` | 前台服务：WebSocket 长连中转服务器，接收 Web/App 控制台指令驱动本地 AI（断线缓存补发 + 序号去重） |
| `remote/AiSyncBus` | 三端同步事件桥（PDA 屏 ↔ 远程服务 ↔ 控制端，事件 source 路由防回环） |
| `remote/RemoteAiSession` | 远程侧 AI 会话执行（与本地 AiViewModel 对称的上下文压缩/历史构建） |
| `util/SosEngine` | 摩斯引擎：手电闪烁 + 2.4kHz 方波循环（AudioTrack）+ GPS 定位 + 短信/拨号 |
| `util/ShareHelper` / `ExportReportButton` | 报告导出（MediaStore Download 目录）与系统分享 |
| `util/ResultCache` | 扫描/检测结果缓存（离线回看，不必重扫） |
| `util/SecurePrefs` | 敏感数据加密存储（Android Keystore AES-GCM） |

## 约定

- 新增功能页三处接线：`TopLevelDestination`（路由）→ `MainScreen`（composable）→ `ToolEntries`（首页入口）
- 依赖 root 的页面必须有常驻 root 状态提示（有能力说明/无能力降级建议）
- 检测类时长一律"默认值 + 可改"，与 AI 工具参数保持一致
