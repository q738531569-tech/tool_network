# 装维网络工具箱（tool_network）

面向**宽带装维工程师**的 Android 网络工具箱：局域网发现 / 网络诊断 / 抓包分析 / 摄像头管理 / PCDN 检测 / 极客工具 / SOS 求救 / AI 排障助手，支持 root 增强。全部中文界面。

当前版本：**1.6.19**（minSdk 26 / targetSdk 34，包名 `com.installer.toolbox`）

## 功能全景

| 分类 | 功能 |
|---|---|
| 🆘 SOS 求救 | 一键求救：手电+蜂鸣摩斯循环、含 GPS 坐标与地图链接的求救短信、一键拨号（紧急联系人 1-3 个） |
| 🌐 局域网发现 | 根级 ARP 扫描 / ping 扫描、mDNS 主机名发现、设备清单持久化（Room）、静默断网（ARP+IPv6 双向欺骗，需 root） |
| 🩺 网络诊断 | Ping、DNS 查询、端口扫描、路由追踪、网关冲突检测、环路检测、ARP 欺骗检测、弱网检测（丢包/时延/抖动）、**网线测试**（网口链路/速率/错误帧/丢包判定，需 root）、IP 计算器、SNMP 面板、Wake-on-LAN |
| 📦 抓包分析 | 免 root VpnService 抓包、广播/组播风暴统计告警 |
| 📷 摄像头管理 | ONVIF 自动发现（WS-Discovery+SOAP）、海康 SADP/ISAPI：改 IP/密码/重启/恢复出厂、跨网段直连 |
| ⚡ 极客工具 | 内置 nmap 深度扫描（root）、BLE 扫描、条码生成/扫码连 Wi-Fi（二维码一键连接）、**光猫 Web 助手**（WebView 内嵌+AI 读页面）、Wi-Fi 分析/雷达、DFS 信道检查、踢下线 |
| 📈 PCDN 检测 | 全网 PCDN 设备两阶段检测（9 大特征：上行流量/UDP 占比/IDC 目标/STUN/SSDP/UPnP/DNS 量等）、ARP 中间人流量审计（需 root） |
| 🤖 AI 排障助手 | DeepSeek 驱动、53 个工具调用闭环、root shell 逐步审批、按会话隔离的长期记忆与档案（.md）、凭据保险库（Keystore 加密+对话打码）、上下文自动压缩与缓存命中统计 |
| 🕹 远程控制 | 设备前台服务 WS 长连中转服务器，Web 控制台与 App 内置控制台远程驱动 AI；会话/记忆/删除三端实时同步；**远程强制升级**（下发即更新+自动重启）、开机自启 |

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose（BOM 2024.10.01）+ Material3 + Navigation Compose
- AGP 8.7.3 / Gradle 8.9 / JVM 17 / Room 2.6.1(KSP) / OkHttp / dnsjava / snmp4j
- 7 个模块：`app`、`core-common`、`core-network`、`core-capture`、`core-isapi`、`core-agent`、`core-router`（已放弃，源码保留）
- 内置 root 工具（免 Termux）：`assets/` 直装 nmap / tcpdump / arpspoof / deauth 静态二进制，自研 C 源码见 `native-tools/`

## 快速开始

1. **环境**：JDK 17 + Android SDK（platform-34），Gradle 由 wrapper 自动下载。
2. **本地配置**：新建 `local.properties`：

   ```properties
   sdk.dir=/path/to/android-sdk
   # release 签名需要（debug 可省略）：
   KEYSTORE_PASSWORD=...
   KEY_PASSWORD=...
   KEY_ALIAS=...
   ```

3. **替换服务器占位域名**：源码与文档中的 `your-server.example` 是占位符，全局搜索替换为你自己的服务器地址（涉及 OTA 更新源、反馈接口、远程控制中转三处，见下）。
4. **编译**：

   ```bash
   ./gradlew assembleDebug      # 调试包
   ./gradlew assembleRelease    # 正式包（需 keystore）
   ```

## root 说明

无 root 时绝大多数功能可用（发现/诊断/抓包/摄像头/AI 对话）。以下功能依赖 root（Magisk `su`）：内置 nmap/tcpdump、根级 ARP 扫描、ARP 中间人流量审计、静默断网、踢下线、AI root shell。各功能页内均有 root 状态提示与降级建议。

## 服务器端组件（可选自建）

App 的 OTA / 反馈 / 远程控制三个能力默认指向作者服务器，开源用户可自行部署：

| 组件 | 位置 | 说明 |
|---|---|---|
| OTA 更新 | 静态目录 + `version.json` | 修改 `app/ota/UpdateModels.kt` 的 `DEFAULT_UPDATE_URL`；`version.json` 格式：`{"latestVersion":"...","apkUrl":"app-release.apk","changelog":"...","size":0,"md5":""}` |
| 反馈收集 | `scripts/feedback.php` + `scripts/toolbox_admin.php` | PHP 8 + MySQL（建库 `toolbox`，表 `tb_feedback`）；管理页密码在 `toolbox_admin.php` 顶部 `CHANGE_ME` 处修改 |
| 远程控制中转 | `scripts/remote-server/` | Node 20 WebSocket 中转（Docker 部署，默认 8091），详见其 [README](scripts/remote-server/README.md)；App 端改 `RemoteControlService.kt` / `RemoteControlViewModel.kt` 的 `WS_URL` |

## 免责声明

- 静默断网、踢下线、ARP 欺骗、信道阻塞、摄像头改密/恢复出厂等**主动操作类功能**会影响现场其他设备，请在授权范围内使用，后果自负。
- SOS 求救会真实发送短信/拨号，注意误触。
- AI 助手调用 DeepSeek API 会产生费用；root shell 工具默认逐步审批。
- 本项目按 **MIT** 许可提供，作者不对使用后果承担任何责任。

## 目录结构

```
app/                  应用壳（首页工作台 / AI 助手 / 设置 / 各功能页）
core-common/          Room 数据库（设备清单/诊断报告）
core-network/         设备发现、诊断、IP 计算器、极客工具、内置工具管理
core-capture/         VpnService 抓包 + 广播风暴检测
core-isapi/           ONVIF + 海康 SADP/ISAPI（含 hikvision.aar）
core-agent/           DeepSeek Agent + 53 工具 + root shell + 记忆/凭据
core-router/          路由器品牌适配（已放弃，源码保留）
native-tools/         自研 C 工具源码（arpspoof/deauth/arpscan/ndpspoof）
scripts/              发布脚本 + 服务端 PHP + Node 中转
```

每个目录均有 README 详述包结构与类职责：[app](app/README.md) · [core-network](core-network/README.md) · [core-agent](core-agent/README.md) · [core-capture](core-capture/README.md) · [core-isapi](core-isapi/README.md) · [core-common](core-common/README.md) · [core-router](core-router/README.md) · [native-tools](native-tools/README.md) · [scripts](scripts/README.md)

## 许可

[MIT](LICENSE) © 2026 黑漂 (q738531569-tech)
