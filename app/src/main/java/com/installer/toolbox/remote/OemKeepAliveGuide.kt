package com.installer.toolbox.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 各厂商 ROM 后台常驻引导（2026-08-19）。
 *
 * 背景：国产 ROM（MIUI / EMUI / MagicUI / ColorOS / OriginOS 等）普遍带"自启动管理 + 后台清理"，
 * 即使 App 有前台服务 + START_STICKY + BOOT_COMPLETED，也可能被系统拦截/冻结：
 * - 开机广播被系统"自启动管理"拦截（App 不自启，远程一直离线）
 * - 后台进程被省电策略清理（START_STICKY 不重建）
 * 代码层无解，必须用户在系统设置里放行。本类按机型识别厂商，给出对应设置路径文案 + 跳转入口。
 */
object OemKeepAliveGuide {

    /** 厂商识别：优先读专有属性（MIUI/EMUI/ColorOS/OriginOS），回退 Build.MANUFACTURER；未知返回 null */
    fun detectOem(): String? {
        if (getProp("ro.miui.ui.version.name").isNotBlank()) return "小米 MIUI"
        if (getProp("ro.build.version.emui").isNotBlank()) {
            return if (Build.MANUFACTURER.contains("honor", true)) "荣耀 MagicUI" else "华为 EMUI"
        }
        if (getProp("ro.build.version.oplusrom").isNotBlank()) return "OPPO ColorOS"
        if (getProp("ro.vivo.os.build.display.id").isNotBlank()) return "vivo OriginOS"
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") -> "小米 MIUI"
            m.contains("honor") -> "荣耀 MagicUI"
            m.contains("huawei") -> "华为 EMUI"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "OPPO ColorOS"
            m.contains("vivo") || m.contains("iqoo") -> "vivo OriginOS"
            m.contains("samsung") -> "三星 OneUI"
            m.contains("meizu") -> "魅族 Flyme"
            m.contains("nubia") || m.contains("zte") -> "努比亚/中兴"
            else -> null
        }
    }

    /** 各厂商引导文案：自启动允许 + 后台无限制的完整步骤（未知机型给通用提示） */
    fun guideText(oem: String?): String {
        return when (oem) {
            "小米 MIUI" ->
                "小米/MIUI：设置 → 应用设置 → 应用管理 → 工具箱 → 自启动（允许）；省电策略（无限制）。"
            "华为 EMUI" ->
                "华为/EMUI：设置 → 应用 → 应用启动管理 → 工具箱 → 手动管理 → 允许自启动/关联启动/后台活动；" +
                    "设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接。"
            "荣耀 MagicUI" ->
                "荣耀/MagicUI：设置 → 应用 → 应用启动管理 → 工具箱 → 手动管理 → 允许自启动/后台活动；" +
                    "电池 → 更多电池设置 → 休眠时始终保持网络连接。"
            "OPPO ColorOS" ->
                "OPPO/ColorOS：设置 → 电池 → 更多设置 → 允许后台运行（工具箱）；" +
                    "设置 → 应用管理 → 工具箱 → 自启动（允许）。"
            "vivo OriginOS" ->
                "vivo/OriginOS：设置 → 电池 → 后台耗电管理 → 工具箱 → 允许后台高耗电；" +
                    "设置 → 应用与权限 → 应用管理 → 工具箱 → 自启动（允许）。"
            "三星 OneUI" ->
                "三星/OneUI：设置 → 电池 → 后台使用限制 → 工具箱 → 设为不受限制；" +
                    "设置 → 应用 → 工具箱 → 电池 → 不受限制。"
            "魅族 Flyme" ->
                "魅族/Flyme：设置 → 应用管理 → 工具箱 → 权限管理 → 后台管理 → 允许后台运行/自启动。"
            "努比亚/中兴" ->
                "努比亚/中兴：设置 → 应用与权限 → 应用管理 → 工具箱 → 自启动（允许）；电池 → 后台运行（允许）。"
            else ->
                "部分系统有自启动/后台清理限制：请在系统设置 → 应用管理 → 工具箱中允许自启动、后台运行不受限制，"
        }
    }

    /** 打开系统设置入口：优先跳厂商自启动管理页（无公开 Intent 时兜底应用详情页） */
    fun openSettings(context: Context) {
        val oem = detectOem()
        val intent: Intent? = when (oem) {
            // 小米 MIUI：安全中心 → 自启动管理页（精准直达，非权限页）
            "小米 MIUI" -> runCatching {
                Intent().setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }.getOrNull()
            // 华为/荣耀：应用启动管理页（无标准 Intent，跳应用详情兜底）
            "华为 EMUI", "荣耀 MagicUI" -> runCatching {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + context.packageName))
            }.getOrNull()
            else -> null
        }
        val target = intent ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:" + context.packageName))
        try {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(target)
        } catch (e: Exception) {
            // 极端兜底：详情页也打不开就放弃（用户可手动去设置）
        }
    }

    /** 读系统属性（反射 SystemProperties，避免依赖隐藏 API） */
    private fun getProp(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, key) as? String ?: ""
    } catch (e: Exception) {
        ""
    }
}
