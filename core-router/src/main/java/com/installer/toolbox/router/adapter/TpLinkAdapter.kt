package com.installer.toolbox.router.adapter

import com.installer.toolbox.router.HtmlStatusParser
import com.installer.toolbox.router.RouterAdapter
import com.installer.toolbox.router.RouterBrand
import com.installer.toolbox.router.RouterStatus

/**
 * TP-Link 适配器(家用路由器系列,含运营商定制版)。
 *
 * 页面路径为公开资料中的常见路径,**未真机验证,待现场校准**;
 * 登录/状态逻辑靠通用 HTML 关键字解析兜底。
 */
object TpLinkAdapter : RouterAdapter {

    override val brand: RouterBrand = RouterBrand.TP_LINK

    /**
     * 账密候选:TP-Link 常见默认 admin/admin 与空口令,仅作尝试未验证。
     */
    override fun loginCandidates(): List<Pair<String, String>> = listOf(
        "admin" to "admin",
        "admin" to "",
        "root" to "root"
    )

    /** 登录页路径候选(老版 / 即登录页;新版 webpages 目录) */
    override fun loginPagePaths(): List<String> = listOf(
        "/",
        "/webpages/index.html"
    )

    /** 状态页路径候选(新版 TP-Link 管理页入口,状态信息常直接呈现在首页) */
    override fun statusPagePaths(): List<String> = listOf(
        "/",
        "/webpages/index.html",
        "/webpages/status.html"
    )

    /** 通用关键字解析,解析不到返回空字段,不抛异常 */
    override fun parseStatus(html: String): RouterStatus = HtmlStatusParser.parse(html, brand)
}
