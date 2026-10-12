# core-isapi — 摄像头发现与管理（ONVIF + 海康）

安防摄像头协议层：ONVIF 标准协议 + 海康私有协议（SADP/ISAPI），支持跨网段发现与免旧密码重置。

## 类清单

| 类 | 职责 |
|---|---|
| `OnvifDiscovery` | WS-Discovery 组播探测（`239.255.255.250:3702`），解析 ProbeMatch 提取服务地址与设备型号 |
| `OnvifClient` | ONVIF SOAP 客户端：设备信息/媒体 URI/改 IP/改密码（WS-Security Digest 鉴权）；OkHttp 强制 `Proxy.NO_PROXY`（防系统代理残留干扰） |
| `SadpDiscoverer` | 海康 SADP 组播发现（`239.255.255.250:37031`）：**跨网段**也能发现同二层的海康设备 |
| `HikvisionApi` | 海康 ISAPI 操作：激活、改 IP/端口/密码、重启、恢复出厂、快照——经 `libs/hikvision.aar`（gomobile 封装的官方 SDK，编译必需，勿删） |
| `PasswordProbe` | 弱口令探测：按常见口令表**逐个试**（非爆破，避免触发海康锁定），配合 `OnvifClient`/`HikvisionApi` 验证 |

## 典型流程

1. `OnvifDiscovery` / `SadpDiscoverer` 发现设备
2. 识别型号 → 选择 ONVIF 或海康通道
3. 管理操作（改 IP/密码/重启/恢复出厂；海康支持免旧密码重置）
4. 跨网段直连：手动录入 IP 后仍可走 ISAPI/ONVIF 管理
