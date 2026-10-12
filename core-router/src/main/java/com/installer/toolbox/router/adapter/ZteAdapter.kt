package com.installer.toolbox.router.adapter

import com.installer.toolbox.router.HtmlStatusParser
import com.installer.toolbox.router.RouterAdapter
import com.installer.toolbox.router.RouterBrand
import com.installer.toolbox.router.RouterStatus

/**
 * 中兴适配器(中兴光猫/FTTR/路由器系列)。
 *
 * 页面路径为公开资料中的常见路径,**未真机验证,待现场校准**;
 * 登录/状态逻辑靠通用 HTML 关键字解析兜底。
 */
object ZteAdapter : RouterAdapter {

    override val brand: RouterBrand = RouterBrand.ZTE

    /**
     * 账密候选:运营商常见默认口令,仅作尝试未验证——
     * admin/admin、admin/Zte521、user/user、CMCCAdmin/aDm8H%MdA 等。
     */
    override fun loginCandidates(): List<Pair<String, String>> = listOf(
        "admin" to "admin",
        "admin" to "Zte521",
        "admin" to "",
        "user" to "user",
        "CMCCAdmin" to "aDm8H%MdA",
        "telecomadmin" to "nE7jA%5m"
    )

    /** 登录页路径候选(中兴设备常见 getpage.gch 入口) */
    override fun loginPagePaths(): List<String> = listOf(
        "/",
        "/getpage.gch?pid=1002",
        "/cgi-bin/webproc"
    )

    /** 状态页路径候选(中兴 WAN 状态页常见 nextpage 参数形式) */
    override fun statusPagePaths(): List<String> = listOf(
        "/getpage.gch?pid=1002&nextpage=wan_status_t.gch",
        "/getpage.gch?pid=1002&nextpage=index_t.gch",
        "/getpage.gch?pid=1002"
    )

    /** 通用关键字解析,解析不到返回空字段,不抛异常 */
    override fun parseStatus(html: String): RouterStatus = HtmlStatusParser.parse(html, brand)
}
