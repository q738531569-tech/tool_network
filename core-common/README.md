# core-common — 数据层（Room）

设备清单与诊断报告的本地持久化，被 app 与各功能模块共用。

## 类清单

| 类 | 职责 |
|---|---|
| `data/ToolboxDatabase` | Room 数据库单例（设备清单表 + 诊断报告表） |
| `data/DeviceEntity` / `DeviceDao` | 局域网设备清单（发现页"加入清单"，跨扫描保留） |
| `data/DiagReportEntity` / `DiagReportDao` | 诊断报告存档（时间/结论/详情，可导出） |
| `util/UsageStats` | 功能使用次数统计（设置页展示，辅助优化排序） |

## 跨模块约定

⚠️ 本模块的 `room-runtime` 必须以 **`api`** 而非 `implementation` 暴露——否则 app 编译报"supertype 不可见"（DAO 接口继承 `RoomDatabase` 的可见性问题）。
