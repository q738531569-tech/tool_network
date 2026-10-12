# core-capture — 免 root 抓包与风暴检测

基于 `VpnService` 的抓包引擎：以"本地 VPN"方式接管设备流量副本，**无需 root** 即可分析广播/组播风暴与流量概况。

## 类清单

| 类 | 职责 |
|---|---|
| `CaptureEngine` | 建立 VpnService（接管 IPv4/IPv6，`addRoute 0.0.0.0/0`），从 tun fd 读原始 IP 包并分发 |
| `PacketCaptureService` | 前台服务承载生命周期。⚠️ 停止必须发 `ACTION_STOP` intent → 关 fd → `stopForeground` → `stopSelf`（`stopService` 对系统绑定的 VpnService 无效） |
| `IpPacketParser` | IPv4/IPv6 报头解析：协议号、源/目的地址、端口、广播/组播判定 |
| `TrafficSnapshot` | 统计快照：按协议/来源设备聚合包数与字节数 |
| `BroadcastStormDetector` | 广播/组播风暴检测：单位时间广播占比超阈值告警（环路/故障设备的典型特征） |
| `CaptureBus` | 抓包结果回传 UI 的事件总线（Service → ViewModel） |

## 使用形态

App「抓包分析」页：选择时长 → 实时统计卡片 + 来源设备排行 → 风暴告警 → 导出/分享报告。
