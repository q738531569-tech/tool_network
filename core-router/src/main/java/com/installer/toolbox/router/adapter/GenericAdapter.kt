package com.installer.toolbox.router.adapter

import com.installer.toolbox.router.RouterAdapter
import com.installer.toolbox.router.RouterBrand
import com.installer.toolbox.router.RouterStatus

/**
 * 通用适配器:品牌未识别时只做基础探测(不尝试登录),提示手动打开管理页。
 */
object GenericAdapter : RouterAdapter {

    override val brand: RouterBrand = RouterBrand.GENERIC

    /** 无账密候选:不猜测未知设备的默认口令 */
    override fun loginCandidates(): List<Pair<String, String>> = emptyList()

    /** 无登录页候选 */
    override fun loginPagePaths(): List<String> = emptyList()

    /** 无状态页候选 */
    override fun statusPagePaths(): List<String> = emptyList()

    /** 直接返回提示,不做解析 */
    override fun parseStatus(html: String): RouterStatus = RouterStatus(
        brand = brand,
        note = "未识别品牌,无法自动登录,请手动打开管理页"
    )
}
