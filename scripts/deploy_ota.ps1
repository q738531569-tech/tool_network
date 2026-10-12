# ============================================================
# 自动发布 OTA：编译 APK（智能跳过）→ 生成 version.json → 推送到 88 服务器 OTA 目录
#
# 用法：
#   pwsh -File scripts\deploy_ota.ps1 -Variant release -Changelog "新增 XX 功能"
#   pwsh -File scripts\deploy_ota.ps1 -Variant release -SkipBuild -Changelog "..."   # 跳过编译直接推（APK 已编译好时用）
#
# 说明：
#   - 默认 Variant=debug：测试机装的是 debug 签名版，只有同签名才能 OTA 升级；
#     正式版（release 签名，PDA/正式用户）改用 -Variant release；**OTA 永远推 release**
#   - **智能跳过编译**：APK 已存在且比全部源码/构建脚本新（增量编译等价条件）时自动跳过，
#     省去重复编译；-SkipBuild 强制跳过（你自己保证 APK 是最新的）
#   - 版本号自动从 app/build.gradle.kts 的 versionName 读取
#   - 推送通道：SSH/SFTP（paramiko）到服务器 192.168.10.88，
#     目标目录 /www/wwwroot/your-server.example_8090/toolbox/ota/
#     （App 更新源 http://your-server.example:8090/toolbox/ota/，见 UpdateModels.kt）
#   - 本脚本含 && 等 pwsh 语法：**必须用 pwsh 跑**（PowerShell 5.1 会语法报错）
# ============================================================
param(
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",
    [string]$Changelog = "修复与优化",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot   # F:\Reasonix_work\network
$apk = Join-Path $root "app\build\outputs\apk\$Variant\app-$Variant.apk"
$apkName = "app-$Variant.apk"

# 1) 编译（智能跳过：APK 比全部源码/构建脚本新 → 无需重编）
$needBuild = $true
if ($SkipBuild) {
    Write-Host "[1/4] 跳过编译（-SkipBuild，直接使用现有 APK）"
    $needBuild = $false
} elseif (Test-Path $apk) {
    # 最新源码/构建脚本时间（排除 build/.gradle 产物目录，避免自干扰）
    $latestSrc = Get-ChildItem $root -Recurse -File -Include *.kt, *.kts, *.java, *.xml, *.json, *.properties |
        Where-Object { $_.FullName -notmatch '\\build\\|\\.gradle\\|\\.idea\\|\\.git\\' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $apkTime = (Get-Item $apk).LastWriteTime
    if ($null -ne $latestSrc -and $apkTime -ge $latestSrc.LastWriteTime) {
        Write-Host "[1/4] APK 已是最新（$($apkTime.ToString('MM-dd HH:mm:ss'))，晚于全部源文件），跳过编译"
        $needBuild = $false
    }
}
if ($needBuild) {
    Write-Host "[1/4] 编译 $Variant ..."
    $task = "assemble" + $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
    wsl bash -lc "cd /mnt/f/Reasonix_work/network && ./gradlew $task --console=plain 2>&1 | tail -3"
}
if (-not (Test-Path $apk)) { throw "编译产物不存在: $apk" }

# 2) 版本号（自动从 build.gradle.kts 读取）+ size + md5
$verLine = Select-String -Path (Join-Path $root "app\build.gradle.kts") -Pattern 'versionName = "([^"]+)"' | Select-Object -First 1
$version = if ($verLine) { $verLine.Matches[0].Groups[1].Value } else { "1.0.0" }
$size = (Get-Item $apk).Length
$md5 = (Get-FileHash $apk -Algorithm MD5).Hash.ToLower()

# 3) 生成 version.json（与 App 端 UpdateModels 约定一致）
$json = @{
    latestVersion = $version
    apkUrl        = $apkName
    changelog     = $Changelog
    size          = $size
    md5           = $md5
} | ConvertTo-Json

# 4) 推送（SSH/SFTP paramiko 到 88 服务器，含清理历史 APK）
Write-Host "[2/4] 推送 $apkName（$version, $([math]::Round($size/1MB,1))MB, md5 $md5）→ 88 服务器 toolbox/ota/"
python (Join-Path $PSScriptRoot "_ota_sftp_88.py") $apk $apkName $json
if ($LASTEXITCODE -ne 0) { throw "SFTP 推送失败（88 服务器不可达或认证失败）" }
Write-Host "[3/4] 已通过 SSH/SFTP 推送并清理历史 APK"

Write-Host "[4/4] OTA 发布完成：$apkName（版本 $version）——App 启动将自动检测并弹更新"
