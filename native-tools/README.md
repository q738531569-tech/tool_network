# native-tools — 内置 root 工具的 C 源码

App 内置的 arm64 网络工具源码（产物打进 `app/src/main/assets/`，运行时由 `BuiltinTools` 解压并经 root 复制到 `/data/local/tmp` 执行——SELinux 禁止在 `/data/data` 下直接执行）。

## 源码清单

| 文件 | 产物 | 说明 |
|---|---|---|
| `_arpspoof.c` | `assets/arpspoof` | 定向 ARP 欺骗（只毒指定目标，比通用 arpspoof 温和）；静默断网/流量审计的核心 |
| `_ndpspoof.c` | `assets/ndpspoof` | IPv6 NDP 欺骗（纯 `AF_PACKET`，无第三方依赖）：宣称网关 v6 地址/MAC，配合 arpspoof 实现**双向断网** |
| `_deauth.c` | `assets/deauth` | 802.11 deauth 帧发送（踢下线，需网卡 monitor 能力，部分设备可用） |
| `_arpscan.c` | —（备用） | 广播 ARP 扫描；因 AP 隔离下常无响应，主扫描已改用并发 ping + ARP 表 |
| `_strerror_stub.c` | 链接桩 | arpspoof 链接 libpcap 时补 `strerror_l` 缺失符号 |

外部工具（非本目录源码，随 assets 分发）：`nmap`（静态编译 + `nmap_data/`）、`tcpdump`（`--with-crypto=no` 静态编译）。

## 重新编译（NDK 交叉编译 arm64）

```bash
# 通用（ndpspoof 这类无依赖的）：
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
    -fPIE -pie -O2 -o ndpspoof _ndpspoof.c

# arpspoof（需 libpcap + 16KB 页对齐修 TLS，Android 15+ 必需）：
$NDK/.../aarch64-linux-android26-clang -fPIE -pie -O2 \
    -I/path/to/libpcap -Wl,-z,max-page-size=16384 \
    -o arpspoof _arpspoof.c _strerror_stub.c /path/to/libpcap.a
```

产物放回 `app/src/main/assets/` 即随 APK 分发。
