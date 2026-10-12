# core-router — 路由器品牌适配（已放弃，源码保留）

M4 里程碑曾实现"路由器管理"（登录各品牌路由后台查终端列表），第一版真机验证通过后，因各品牌后台差异大、维护成本高，**用户决定放弃**：入口与依赖已移除，源码保留。

## 结构

| 文件 | 职责 |
|---|---|
| `RouterService` | 对外统一入口（登录/查终端） |
| `RouterDetector` | 品牌识别（网关指纹探测，候选 `192.168.1.1` / `192.168.0.1` / `192.168.100.1`） |
| `RouterAdapter` | 适配器接口 |
| `adapter/HuaweiAdapter` 等 5 个 | 华为 / 中兴 / TpLink / OpenWrt / Generic 各品牌登录与终端列表抓取 |

## 恢复方法

在 `settings.gradle.kts` 加回 `include(":core-router")` 即可参与构建（App 侧入口需重新接线）。
