package com.installer.toolbox.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 路由器管理门面:对外只暴露两个挂起函数——
 * [probe] 品牌识别、[fetchStatus] 登录 + 状态抓取。
 *
 * 所有网络操作均切 IO 线程、内部 try/catch,**不向 UI 抛异常**;
 * 客户端统一 NO_PROXY 直连(绕过系统代理),超时 3 秒。
 */
object RouterService {

    /** 表单字段名候选:各品牌登录表单常见字段组合 */
    private val LOGIN_FIELD_CANDIDATES = listOf(
        "username" to "password",
        "Username" to "Password",
        "user" to "pass",
        "userName" to "Passwd",
        "luci_username" to "luci_password" // OpenWrt LuCI 标准字段
    )

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /** 品牌识别:gatewayIp 为空时先取当前网络网关,再退回候选列表探测 */
    suspend fun probe(context: Context? = null, gatewayIp: String? = null): RouterProbeResult =
        withContext(Dispatchers.IO) {
            val gw = gatewayIp?.takeIf { it.isNotBlank() }?.trim()
                ?: context?.let { currentGatewayIp(it) }
            RouterDetector.detect(gw, newClient())
        }

    /** 从系统 DHCP 信息取当前默认网关 IP(Android 多网络策略下最直接) */
    private fun currentGatewayIp(context: Context): String? = try {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val gw = wifi.dhcpInfo?.gateway ?: return null
        // dhcpInfo.gateway 为网络字节序 Int（如 0x010AA8C0 表示 192.168.10.1），需反转字节序
        val v = Integer.reverseBytes(gw).toLong() and 0xffffffffL
        "${(v shr 24) and 0xff}.${(v shr 16) and 0xff}.${(v shr 8) and 0xff}.${v and 0xff}"
    } catch (e: Exception) {
        null
    }

    /**
     * 登录并抓取状态。
     *
     * 流程:选适配器 → 候选登录页逐个试(POST/GET 表单,OkHttp CookieJar 保存会话)
     * → 成功后按候选状态页抓取 → parseStatus 解析。
     * 全部失败返回带 note 的 [RouterStatus],**不抛异常**。
     *
     * @param account 用户输入的账号(UI 默认预填适配器第一个候选)
     * @param password 用户输入的密码
     */
    suspend fun fetchStatus(
        probe: RouterProbeResult,
        account: String,
        password: String
    ): RouterStatus = withContext(Dispatchers.IO) {
        try {
            val adapter = RouterAdapterFactory.forBrand(probe.brand)
            if (adapter.loginPagePaths().isEmpty()) {
                // Generic 等无路径可试,直接返回提示
                return@withContext RouterStatus(
                    brand = probe.brand,
                    note = "未识别品牌,无法自动登录,请手动打开管理页"
                )
            }
            val jar = MemoryCookieJar()
            val client = newClient(jar)
            val baseUrl = probe.adminUrl?.let { extractBase(it) } ?: "http://${probe.gatewayIp}"

            // 1. 登录:只用用户输入的账密(+空密码兜底)。不循环默认密码——
            //    现在路由器都是复杂口令,默认密码已无意义,盲目循环还易触发设备失败锁定
            val candidates = buildList {
                add(account to password)
                // 密码非空时补一次空密码(OpenWrt 等默认 root 无密码)做兜底
                if (password.isNotEmpty()) add(account to "")
            }.distinct()

            val login = tryLogin(client, jar, adapter, baseUrl, candidates)
            if (!login.success) {
                return@withContext RouterStatus(
                    brand = probe.brand,
                    note = "登录/状态抓取失败(模板待现场校准):${login.message}"
                )
            }

            // 2. 抓状态页候选,第一个成功的页面交给适配器解析
            for (path in adapter.statusPagePaths()) {
                try {
                    val request = Request.Builder().url(joinUrl(baseUrl, path)).build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val html = resp.body?.string() ?: ""
                            return@withContext adapter.parseStatus(html)
                        }
                    }
                } catch (e: Exception) {
                    // 该路径失败,继续下一个候选
                }
            }

            RouterStatus(
                brand = probe.brand,
                note = "登录成功(${login.message})但状态页全部抓取失败(模板待现场校准)"
            )
        } catch (e: Exception) {
            RouterStatus(
                brand = probe.brand,
                note = "登录/状态抓取失败(模板待现场校准):${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    // ------------------------------------------------------------------
    // 登录尝试
    // ------------------------------------------------------------------

    /**
     * 逐路径 × 逐账密 × 逐字段名尝试登录:
     * - POST 表单(主流);再补一次 GET 带参(中兴 getpage.gch 等 GET 型登录)
     * - 判定成功 = 响应 2xx/3xx 且响应体不再包含登录表单特征(如 type="password" 输入框)
     * - 会话 cookie 由 CookieJar 自动保存
     */
    private fun tryLogin(
        client: OkHttpClient,
        jar: MemoryCookieJar,
        adapter: RouterAdapter,
        baseUrl: String,
        candidates: List<Pair<String, String>>
    ): LoginResult {
        for (path in adapter.loginPagePaths()) {
            val url = joinUrl(baseUrl, path)
            for ((user, pass) in candidates) {
                // 品牌优先字段,再补通用字段(去重,避免重复尝试)
                val fields = (adapter.preferredLoginFields() + LOGIN_FIELD_CANDIDATES).distinct()
                for ((userField, passField) in fields) {
                    // POST 表单
                    val form = FormBody.Builder()
                        .add(userField, user)
                        .add(passField, pass)
                        .build()
                    val postReq = Request.Builder().url(url).post(form).build()
                    if (attempt(client, postReq)) {
                        return LoginResult(
                            success = true,
                            sessionCookies = jar.snapshot(),
                            adminUrl = url,
                            message = "账密 $user / ${mask(pass)} 在 $path 登录成功"
                        )
                    }
                    // GET 带参数(部分设备登录入口为 GET 风格)
                    val sep = if (url.contains("?")) "&" else "?"
                    val getReq = Request.Builder()
                        .url("$url$sep$userField=$user&$passField=$pass")
                        .build()
                    if (attempt(client, getReq)) {
                        return LoginResult(
                            success = true,
                            sessionCookies = jar.snapshot(),
                            adminUrl = url,
                            message = "账密 $user / ${mask(pass)} 在 $path 登录成功"
                        )
                    }
                }
            }
        }
        return LoginResult(
            success = false,
            sessionCookies = emptyMap(),
            adminUrl = null,
            message = "所有候选登录路径/账密均失败(可能已改默认口令,或页面路径与模板不符)"
        )
    }

    /** 单次请求尝试:成功判定 = 2xx/3xx 且响应体不含登录表单特征 */
    private fun attempt(client: OkHttpClient, request: Request): Boolean {
        return try {
            client.newCall(request).execute().use { resp ->
                resp.isSuccessful && !isLoginPage(resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 判定响应体是否仍是登录页:仅看是否存在密码输入框。
     * 注意不能看"login+password 关键字同时出现"——登录成功后的 LuCI 首页 HTML
     * （JS/菜单）里必然同时含这两个词,会误判为未登录导致"密码正确仍失败"。 */
    private fun isLoginPage(html: String): Boolean {
        val t = html.lowercase()
        return t.contains("type=\"password\"") ||
            t.contains("type='password'")
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 共享客户端:NO_PROXY 直连 + 3 秒超时;可选 CookieJar 保存会话 */
    private fun newClient(cookieJar: CookieJar? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(RouterDetector.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(RouterDetector.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(RouterDetector.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (cookieJar != null) builder.cookieJar(cookieJar)
        return builder.build()
    }

    /** 从完整 URL 提取协议 + 主机 + 端口(去掉路径),用于拼接候选路径 */
    private fun extractBase(url: String): String {
        return try {
            val u = URL(url)
            val port = if (u.port != -1 && u.port != 80 && u.port != 443) ":${u.port}" else ""
            "${u.protocol}://${u.host}$port"
        } catch (e: Exception) {
            url
        }
    }

    /** 拼接 base 与候选路径("/" 原样返回) */
    private fun joinUrl(base: String, path: String): String =
        if (path == "/") base else "$base/${path.trimStart('/')}"

    /** 密码脱敏展示(仅用于日志/提示,避免明文) */
    private fun mask(pass: String): String =
        if (pass.isEmpty()) "(空)" else pass.take(1) + "***"

    // ------------------------------------------------------------------
    // 会话 Cookie 容器(线程安全)
    // ------------------------------------------------------------------

    /** 内存 CookieJar:按主机保存,可导出快照用于展示会话信息 */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host] ?: emptyList()

        fun snapshot(): Map<String, String> =
            store.values.flatten().associate { it.name to it.value }
    }
}
