#!/usr/bin/env bash
# ============================================================
# 自动发布 OTA：编译 APK（智能跳过）→ 生成 version.json → 推送 88 服务器 OTA 目录（bash 版，本机 Linux 跑）
#
# 用法：
#   ./scripts/deploy_ota.sh -V release -C "新增 XX 功能"
#   ./scripts/deploy_ota.sh -V release -SkipBuild -C "..."   # 跳过编译直接推（APK 已编译好时用）
#   ./scripts/deploy_ota.sh -V release -DryRun -C "..."      # 只走流程不推送（测试用）
#
# 说明：
#   - 默认 Variant=debug：测试机是 debug 签名；正式版（PDA/正式用户）用 -Variant release；**OTA 永远推 release**
#   - **智能跳过编译**：APK 已存在且比全部源码/构建文件新（增量编译等价条件）时自动跳过；
#     -SkipBuild 强制跳过（你自己保证 APK 是最新的）
#   - 版本号自动从 app/build.gradle.kts 的 versionName 读取
#   - 推送通道：scripts/_ota_sftp_88.py（paramiko SSH/SFTP，**服务器地址与密码在该脚本内，不入库**）
#     目标目录 /www/wwwroot/your-server.example_8090/toolbox/ota/
#     （App 更新源 http://your-server.example:8090/toolbox/ota/，见 UpdateModels.kt）
#   - Windows/WSL 侧旧版 deploy_ota.ps1 已退役（保留仅作参考）
# ============================================================
set -euo pipefail

VARIANT="debug"
CHANGELOG="修复与优化"
SKIP_BUILD=0
DRY_RUN=0

usage() { echo "用法: $0 [-V debug|release] [-C \"更新说明\"] [-SkipBuild] [-DryRun]"; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        -V) VARIANT="${2:?缺少 -V 参数}"; shift 2 ;;
        -C) CHANGELOG="${2:?缺少 -C 参数}"; shift 2 ;;
        -SkipBuild) SKIP_BUILD=1; shift ;;
        -DryRun) DRY_RUN=1; shift ;;
        -h|--help) usage ;;
        *) echo "未知参数: $1"; usage ;;
    esac
done
[[ "$VARIANT" == "debug" || "$VARIANT" == "release" ]] || { echo "Variant 只能是 debug/release"; usage; }

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="$root/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
apk_name="app-$VARIANT.apk"

# 1) 编译（智能跳过：APK 比全部源码/构建文件新 → 无需重编）
need_build=1
if [[ $SKIP_BUILD -eq 1 ]]; then
    echo "[1/4] 跳过编译（-SkipBuild，直接使用现有 APK）"
    need_build=0
elif [[ -f "$apk" ]]; then
    # 任一源码/构建文件比 APK 新 → 需重编（排除构建产物与隐藏目录；与旧 ps1 相同扩展名清单）
    newer=$(find "$root" \
        \( -path "$root/app/build" -o -path '*/build' -o -path '*/.gradle'* -o -path '*/.git'* -o -path '*/.idea'* -o -path '*/.kotlin'* \) -prune -o \
        -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.java' -o -name '*.xml' -o -name '*.json' -o -name '*.properties' \) -newer "$apk" -print -quit)
    if [[ -z "$newer" ]]; then
        echo "[1/4] APK 已是最新（$(date -r "$apk" '+%m-%d %H:%M:%S')，晚于全部源文件），跳过编译"
        need_build=0
    fi
fi
if [[ $need_build -eq 1 ]]; then
    echo "[1/4] 编译 $VARIANT ..."
    task="assemble$(printf '%s' "$VARIANT" | sed 's/^./\U&/')"  # assembleDebug/assembleRelease
    (cd "$root" && ./gradlew "$task" --console=plain 2>&1 | tail -3)
fi
[[ -f "$apk" ]] || { echo "错误：编译产物不存在: $apk" >&2; exit 1; }

# 2) 版本号（自动从 build.gradle.kts 读取）+ size + md5
version=$(grep -oP 'versionName = "\K[^"]+' "$root/app/build.gradle.kts" | head -1)
version="${version:-1.0.0}"
size=$(stat -c%s "$apk")
md5=$(md5sum "$apk" | cut -d' ' -f1)

# 3) 生成 version.json（与 App 端 UpdateModels 约定一致；python 保证 JSON 转义正确）
json=$(python3 - "$version" "$apk_name" "$CHANGELOG" "$size" "$md5" <<'PYEOF'
import json, sys
print(json.dumps({
    "latestVersion": sys.argv[1],
    "apkUrl": sys.argv[2],
    "changelog": sys.argv[3],
    "size": int(sys.argv[4]),
    "md5": sys.argv[5],
}, ensure_ascii=False))
PYEOF
)

# 4) 推送（paramiko SSH/SFTP；服务器地址密码在 _ota_sftp_88.py 里，该脚本不入库）
sftp_py="$root/scripts/_ota_sftp_88.py"
[[ -f "$sftp_py" ]] || { echo "错误：缺少 $sftp_py（含服务器凭据的本地脚本，见 INTERNAL.md）" >&2; exit 1; }
size_mb=$(awk "BEGIN{printf \"%.1f\", $size/1048576}")
echo "[2/4] 推送 $apk_name（$version, ${size_mb}MB, md5 $md5）→ 88 服务器 toolbox/ota/"
if [[ $DRY_RUN -eq 1 ]]; then
    echo "      [DryRun] 跳过推送与校验，version.json 内容预览："
    echo "      $json"
else
    python3 "$sftp_py" "$apk" "$apk_name" "$json" || { echo "错误：SFTP 推送失败（88 服务器不可达或认证失败）" >&2; exit 1; }
    echo "[3/4] 已通过 SSH/SFTP 推送并清理历史 APK"
    # 校验（非致命）：拉服务器 version.json 核对版本号
    remote_ver=$(curl -s --max-time 10 http://192.168.10.88:8090/toolbox/ota/version.json || true)
    if grep -Eq "\"latestVersion\"[[:space:]]*:[[:space:]]*\"$version\"" <<< "$remote_ver"; then
        echo "      校验通过：服务器 version.json latestVersion=$version"
    else
        echo "      ⚠️ 校验未过，请手动核对 http://your-server.example:8090/toolbox/ota/version.json"
    fi
fi

echo "[4/4] OTA 发布完成：$apk_name（版本 $version）——App 启动将自动检测并弹更新"
