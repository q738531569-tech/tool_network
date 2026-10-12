package com.installer.toolbox.router.adapter

import com.installer.toolbox.router.HtmlStatusParser
import com.installer.toolbox.router.RouterAdapter
import com.installer.toolbox.router.RouterBrand
import com.installer.toolbox.router.RouterStatus

/**
 * 华为适配器(华为光猫/FTTR 系列,含 e-Link 系统)。
 *
 * 页面路径为公开资料中的常见路径,**未真机验证,待现场校准**;
 * 登录/状态逻辑靠通用 HTML 关键字解析兜底。
 */
object HuaweiAdapter : RouterAdapter {

    override val brand: RouterBrand = RouterBrand.HUAWEI

    /**
     * 账密候选:运营商常见默认口令,仅作尝试未验证——
     * 电信 telecomadmin/admintelecom、nE7jA%5m;useradmin 空/弱口令;
     * 移动 CMCCAdmin/aDm8H%MdA;通用 admin/admin。
     */
    override fun loginCandidates(): List<Pair<String, String>> = listOf(
        "admin" to "admin",
        "telecomadmin" to "admintelecom",
        "telecomadmin" to "nE7jA%5m",
        "useradmin" to "",
        "useradmin" to "useradmin",
        "CMCCAdmin" to "aDm8H%MdA",
        "root" to "admin"
    )

    /** 登录页路径候选(华为 e-Link 光猫常见入口) */
    override fun loginPagePaths(): List<String> = listOf(
        "/",
        "/login.cgi",
        "/asp/Login.asp"
    )

    /** 状态页路径候选(华为 e-Link 状态页常见路径) */
    override fun statusPagePaths(): List<String> = listOf(
        "/html/amp/status/status_wan_conn.asp",
        "/html/bbsp/common/status.asp",
        "/status/status_wan_conn.asp"
    )

    /** 通用关键字解析,解析不到返回空字段,不抛异常 */
    override fun parseStatus(html: String): RouterStatus = HtmlStatusParser.parse(html, brand)
}
