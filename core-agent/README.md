# core-agent — AI 排障助手（DeepSeek Agent）

DeepSeek 驱动的装维排障 Agent：对话 + 工具调用闭环 + root 系统控制 + 记忆/档案/凭据。

## 核心（`core/agent/`）

| 类 | 职责 |
|---|---|
| `AgentEngine` | Agent 主循环：模型 → 调 `AgentTools` 采集数据 → 回传结果 → 结论。正文/思考 **SSE 流式**逐行输出；工具轮数达上限时追加"只准给结论"的收尾请求（不带工具定义），杜绝半截话；`toolGate` 审批门拦截 `root_shell` |
| `AgentTools` | **53 个工具**的 schema 定义 + 分发执行：网络诊断（ping/dns/端口/路由追踪）、扫描（scan_lan/arp_scan/nmap/ble/pcdn）、主动操作（静默/踢线/信道阻塞）、摄像头（发现/操作/弱口令探测）、系统控制（Wi-Fi/蓝牙/飞行模式）、SSH（exec + 交互三工具）、条码/子网/SNMP/WoL、网线测试（cable_test）、光猫页面读取（web_page_read）、报告导出、记忆/档案/凭据、root_shell |
| `AgentPrompts` | 系统提示词：角色=宽带装维助手；工具优先实测不臆断、排障顺序、高风险操作授权红线 |
| `DeepSeekClient` | OpenAI 兼容客户端：非流式/流式（reasoning_content 与 content 增量、tool_calls 按 index 聚合、usage 含缓存命中）、模型列表、思考强度与 thinking.type 兜底 |
| `RootShell` | Magisk/KernelSU `su` 通道（绝对路径探测），AI 控制系统级功能 |
| `RemoteExecutor` | SSH + Telnet 统一封装（免 root，纯 JVM 实现） |
| `SshSessionPool` | 交互式会话池：`ssh_open / ssh_send / ssh_close` 多轮交互（光猫/交换机 CLI 场景） |
| `InteractiveTerminal` | 终端式交互会话（SSH/Telnet 共用，App 内远程终端页） |

## 支撑（`core/agent/support/`）

| 类 | 职责 |
|---|---|
| `AiMemory` | 长期记忆：AI 经 `remember_info` 主动记忆拓扑/结论/偏好，**按会话隔离**（一个会话一个客户），禁记密码 |
| `AiNotes` | AI 资料档案：`write_md` 写完整报告/资料（按会话隔离），`read_md` 读回；档案清单自动注入上下文 |
| `AiSessionDocs` | 用户导入的会话背景资料（.md/.txt） |
| `CredentialVault` | 凭据保险库：Android Keystore AES-256-GCM 加密落盘；值/清单分离（只给标签不给值）；同步 commit 防进程被杀丢数据 |
| `SensitiveMasker` | 对话密码打码：库值精确替换 `***`（零误伤）+ 正则兜底 |
| `WebPageBridge` | 光猫 Web 助手页面桥：注册当前 WebView，tab 切走时 `onDispose` 抓 DOM 快照，供 AI `web_page_read` 读页面标题/URL/正文（只读不代点按钮，验证码需人工） |
| `FeedbackApi` / `FeedbackHelper` | 反馈上报客户端（POST JSON）/ 诊断信息生成（设备/使用统计/崩溃日志） |
| `AutoReporter` | 启动自动上报：间隔 ≥12h 或含崩溃即报，失败静默 |
| `CrashCollector` | 全局未捕获异常落盘（保留最近 10 份），只含堆栈与版本/机型 |

## 上下文管理

- 历史按**上一轮真实 prompt_tokens** 分档：≥80% 预算带最近 5 轮、≥50% 带 15 轮、其余 30 轮；更早历史由模型生成要点摘要（增量缓存）
- 超限自动降级重试一次（5 轮 + 全量摘要）
- 缓存命中率统计展示（prompt cache hit/miss）
