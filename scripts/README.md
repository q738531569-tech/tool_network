# scripts — 发布脚本与服务端组件

## 发布

| 文件 | 用途 |
|---|---|
| `deploy_ota.ps1` | 一键发布 OTA：编译（可选跳过）→ 读 versionName → 生成 `version.json` → SFTP 推送到 OTA 静态目录。需 PowerShell 7（`pwsh -File deploy_ota.ps1 -Variant release -Changelog "..."`）；服务器凭据读环境变量/交互输入 |

## 服务端（可选自建，配合 App 三个联网能力）

| 文件 | 用途 |
|---|---|
| `feedback.php` | 反馈/自动上报接收 API：POST JSON → MySQL `toolbox.tb_feedback`。顶部改 DB 凭据（`CHANGE_ME`） |
| `toolbox_admin.php` | 反馈 Web 管理页（登录密码在文件顶部 `CHANGE_ME` 修改） |
| `remote-server/` | 远程控制 WebSocket 中转（Node 20 + Docker，默认 8091），详见其 [README](remote-server/README.md) |

## 配套约定

- OTA 目录放 `version.json` + `app-release.apk` 两个文件即可工作（格式见根 README）
- 三个能力的服务器地址在 App 源码中为 `your-server.example` 占位，部署后替换（见根 README「服务器端组件」）
