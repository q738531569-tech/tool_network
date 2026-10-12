# core-network — 网络功能核心库

设备发现、网络诊断、极客扫描与流量分析的实现层（无 UI）。App 功能页与 AI 工具都调用本模块。

## 包结构

### `diagnose/` — 基础诊断

| 类 | 职责 |
|---|---|
| `PingTool` | TCP Ping（对 443 端口 connect 计时）。无 root 无法发原生 ICMP，这是 Android 标准替代 |
| `DnsTool` | 基于 dnsjava 查询 A / AAAA / MX / NS / TXT 五类记录并统计耗时 |
| `TracerouteTool` | TCP 变通路由追踪（逐跳递增端口探测） |
| `PortScanner` | 端口扫描，服务名查 `PortServiceTable` |
| `SubnetCalculator` | IPv4 子网计算器（CIDR/掩码 → 网络号/广播/可用范围，纯 JVM） |
| `WeakNetworkDetector` | 弱网检测：丢包率/时延/抖动分级。有 root 走系统 ping 真 ICMP，无 root 回退 TCP 探测 |
| `SnmpProbe` | SNMP v2c 查询 5 个 system OID（snmp4j） |
| `WakeOnLan` | 向目标 MAC 发送 magic packet 远程开机 |

### `scan/` — 扫描与主动操作（多数需 root）

| 类 | 职责 |
|---|---|
| `BuiltinTools` | 内置 root 工具统一管理：assets → filesDir → root 复制到 `/data/local/tmp` 执行（nmap/tcpdump/arpspoof/deauth） |
| `NmapScanner` | nmap 深度扫描（内置静态 arm64 nmap + nmap_data，`--datadir` 指向解压目录） |
| `DeviceScanner`（discovery 包）配合 | 根级 ARP 扫描：254 条并发 ping + 读 `/proc/net/arp` |
| `ArpSpoofDetector` | ARP 欺骗检测（root + tcpdump，找"一 IP 多 MAC"） |
| `GatewayConflictDetector` | 网关/IP 冲突检测（双 DHCP 维度） |
| `LoopDetector` | 局域网环路检测（广播风暴特征计数） |
| `LanSilencer` | 全网静默/踢下线：ARP + IPv6 NDP **双向欺骗**（arpspoof 定向 + ndpspoof 抢答 NA），含重连自动续静默 |
| `NdpSpoof` | IPv6 NDP 欺骗共享逻辑（LanSilencer 与 TrafficAudit 共用） |
| `TrafficAudit` | ARP/IPv6 中间人流量审计：9 大 PCDN 特征（上行/UDP 占比/分布式 IP/IDC 目标/连接数/STUN/SSDP/UPnP/DNS 量），二级路由借道按源端口还原 |
| `PcdnDetector` / `PcdnNetworkScanner` | 单设备 PCDN 判定 / 全网两阶段一键检测 |
| `IdcTargetDetector` | 目标 IP 是否落 IDC/数据中心网段 |
| `UpnpPortMapper` | UPnP 端口映射表核对（PCDN 特征之一） |
| `LanInterfaceResolver` | 多网络适配：PDA 可同时有 Wi-Fi/有线底座/移动数据，统一解析局域网接口与网段 |
| `BleScanner` | 蓝牙 BLE 扫描 |

### `discovery/` — 设备发现

| 类 | 职责 |
|---|---|
| `DeviceScanner` | 局域网扫描主逻辑（root ARP 并发 ping / 无 root 分段 ping），产出 `HostInfo` |
| `HostInfo` | 设备发现核心数据模型（IP/MAC/厂商/主机名/端口/在线状态） |
| `MacVendorTable` | MAC OUI 厂商识别：内置精简中文表 + 在线兜底两级查询 |
| `MdnsResolver` / `NsdDiscoverer` | mDNS 设备名发现（自实现最小包解析 / Android NsdManager 系统通道） |
| `PortServiceTable` | 端口 → 服务名映射（装维常见服务：Web/SSH/远程桌面/摄像头私有端口/P2P） |
| `BarcodeUtils` | 条码/二维码生成与解码（ZXing core，纯本地） |

### `wifi/` — Wi-Fi 分析

| 类 | 职责 |
|---|---|
| `WifiAnalyzer` | Wi-Fi 信号/信道/邻频分析（反射兼容 Android 14 `SSID/BSSID` 改名） |
| `WifiRadar` | Wi-Fi 信道雷达图（各信道网络数与干扰） |
| `DfsChecker` | DFS 信道航空雷达风险检测（5GHz UNII-2/2e 与雷达共用频段） |

### `util/`

| 类 | 职责 |
|---|---|
| `IpUtil` | 全 App 统一 IP 校验与多网段判断（支持路由器互连多网段属同一 LAN） |
| `ArpTable` | `/proc/net/arp` 统一解析（放宽条目收录规则） |

## root 依赖说明

无 root 时：TCP 系诊断、分段 ping 扫描、VpnService 抓包可用；
需 root（Magisk `su`）：ARP 扫描、tcpdump 系检测、内置 nmap、静默/踢线、流量审计。
