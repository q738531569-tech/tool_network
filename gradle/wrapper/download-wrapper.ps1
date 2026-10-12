# 下载 Gradle 8.9 wrapper jar 到 gradle/wrapper/ 并校验大小。
# 用法：powershell -ExecutionPolicy Bypass -File gradle\wrapper\download-wrapper.ps1
$ErrorActionPreference = "Stop"

$target = Join-Path $PSScriptRoot "gradle-wrapper.jar"
$url = "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"

# 官方 Gradle v8.9.0 文件大小（Git blob 2c3521197d7c4586c843d1d3e9090525f1898cde）
$expectedSize = 43504

Write-Host "正在下载 Gradle 8.9 wrapper jar ..."
Invoke-WebRequest -Uri $url -OutFile $target

$size = (Get-Item $target).Length
Write-Host "下载完成：$target（$size 字节）"

if ($size -ne $expectedSize) {
    Write-Warning "文件大小与官方 v8.9.0 不一致（期望 $expectedSize 字节），请检查网络或重试"
    exit 1
}
Write-Host "校验通过：与官方 Gradle v8.9.0 一致"
