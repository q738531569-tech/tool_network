package com.installer.toolbox.router.adapter

import com.installer.toolbox.router.HtmlStatusParser
import com.installer.toolbox.router.RouterAdapter
import com.installer.toolbox.router.RouterBrand
import com.installer.toolbox.router.RouterStatus

/**
 * OpenWrt 适配器(OpenWrt / ImmortalWrt / LuCI 界面,当前真机网关 rax3000m 即此系)。
 *
 * LuCI 登录走 /cgi-bin/luci POST 表单,会话依赖 Cookie(sysauth);
 * 状态页路径为 LuCI 常见路径,**待真机校准**;解析靠通用 HTML 关键字兜底。
 */
object OpenWrtAdapter : RouterAdapter {

    override val brand: RouterBrand = RouterBrand.OPENWRT

    /**
     * 账密候选:OpenWrt 默认 root 空口令,仅作尝试未验证
     * (装维现场常被改密,失败属正常)。
     */
    override fun loginCandidates(): List<Pair<String, String>> = listOf(
        "root" to "",
        "root" to "admin",
        "admin" to "admin"
    )

    /** 登录页路径候选(LuCI 标准入口) */
    override fun loginPagePaths(): List<String> = listOf(
        "/cgi-bin/luci"
    )

    /** LuCI 标准登录字段优先 */
    override fun preferredLoginFields(): List<Pair<String, String>> = listOf(
        "luci_username" to "luci_password"
    )

    /** 状态页路径候选(LuCI 概览 / 状态 / WAN 页) */
    override fun statusPagePaths(): List<String> = listOf(
        "/cgi-bin/luci/admin/status",
        "/cgi-bin/luci/admin/status/overview",
        "/cgi-bin/luci/admin/network/wan"
    )

    /** 通用关键字解析,解析不到返回空字段,不抛异常 */
    override fun parseStatus(html: String): RouterStatus = HtmlStatusParser.parse(html, brand)
}
