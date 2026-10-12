package com.installer.toolbox.ui.screens.tools

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.installer.toolbox.core.agent.support.WebPageBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 光猫 Web 助手（需求 2026-08-17 登记）：WebView 内嵌登录光猫管理页 + 地址栏/前进后退/刷新。
 * - AI 联动：页面 WebView 注册到 WebPageBridge，AI 的 web_page_read 工具**同进程读 DOM**
 *   （标题/网址/正文）帮用户分析设备信息/日志/状态，绕开光猫登录加密 JS；
 * - 验证码需用户手动输入一次（AI 无法识别图形验证码）；
 * - AI 只读不点按钮：重启/恢复出厂等危险操作不在 AI 能力内。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ModemWebScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // WebView 跨页面复用（2026-08-18 用户反馈：切到 AI 助手后网页挂起、实时参数停）：
    // 全局持有同一 WebView，切 tab 不销毁，网页在后台继续跑（JS 定时器/实时参数不中断），
    // 回到本页复用同一实例（内容/登录态保留），AI 也读实时页面而非静态快照
    val webView = remember { ModemWebHolder.acquire(context) }
    // 默认地址 = 动态读当前网络默认网关（光猫/路由管理地址通常就是网关；读不到才回退 192.168.1.1）
    val defaultUrl = remember {
        "http://" + (defaultGatewayIp(context) ?: "192.168.1.1")
    }
    // 复用实例时标题栏直接显示 WebView 当前实际 URL（重建后不显示默认网关）
    val urlState = remember { mutableStateOf(webView.url?.takeIf { it.isNotBlank() } ?: defaultUrl) }
    val loadingState = remember { mutableStateOf(false) }
    val pageTitle = remember { mutableStateOf("光猫 Web 助手") }
    val canBack = remember { mutableStateOf(false) }
    val canForward = remember { mutableStateOf(false) }
    val loadFailed = remember { mutableStateOf(false) }
    // 参数区（地址栏/快捷芯片/说明条）可收起：默认收起让浏览器最大化显示（2026-08-18 用户反馈）
    val showParams = remember { mutableStateOf(false) }
    // 清除数据后的提示（短时显示）
    val clearedNotice = remember { mutableStateOf(false) }
    // 网关自动切换提示（切网络后自动切到新网关，2026-08-18 用户反馈）
    val gwNotice = remember { mutableStateOf<String?>(null) }

    /** 清除 WebView 缓存/Cookie/表单/DOM 存储（换光猫/页面异常时用；清后重新加载当前地址） */
    fun clearWebData() {
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        webView.evaluateJavascript("localStorage.clear();sessionStorage.clear();", null)
        if (!webView.url.isNullOrBlank()) webView.loadUrl(webView.url!!)
        clearedNotice.value = true
        // 提示 4 秒后消失
        kotlinx.coroutines.MainScope().launch {
            delay(4000)
            clearedNotice.value = false
        }
    }

    // 注册到桥（AI web_page_read 读实时页面）；切 tab 时**不注销不销毁**（网页后台继续跑，
    // 实时参数不中断；重新进入复用同一实例）。页面设置仅首次创建时做一次。
    DisposableEffect(Unit) {
        WebPageBridge.register(webView)
        onDispose {
            // 保持 live：不 unregister、不 destroy（ModemWebHolder 全局持有）
        }
    }

    // WebView 配置（remember 外一次性设置；复用实例重复设置幂等）
    remember {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        // 【背景图修复 2026-08-18 用户反馈网页 background 不显示】：
        // 光猫/路由管理页多为 https 自签证书且页面 CSS 引 http 资源——混合内容与自签证书
        // 默认被 WebView 拦截导致背景图/样式不渲染，需显式放行；图片加载显式开启
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.blockNetworkImage = false
        webView.settings.loadsImagesAutomatically = true
        // UA 伪装 Chrome：去掉 wv 标记，个别站点不识别为内嵌 WebView（渲染能力不受影响）
        webView.settings.userAgentString =
            webView.settings.userAgentString
                .replace("; wv", "")
                .replace(Regex(" Version/\\d+(\\.\\d+)+"), " Chrome/120.0.0.0")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadingState.value = true
                loadFailed.value = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingState.value = false
                urlState.value = url ?: ""
                canBack.value = view?.canGoBack() ?: false
                canForward.value = view?.canGoForward() ?: false
                pageTitle.value = view?.title?.takeIf { it.isNotBlank() } ?: "光猫 Web 助手"
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                // 主框架加载失败才提示（子资源错误忽略）
                if (request?.isForMainFrame == true) loadFailed.value = true
            }

            // 光猫/路由管理页多自签证书：放行（装维现场信任场景；页面可访问性优先）
            override fun onReceivedSslError(
                view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?
            ) {
                handler?.proceed()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ---- 顶栏：返回 + 标题 + 地址栏 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pageTitle.value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = urlState.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = { if (canBack.value) webView.goBack() }, enabled = canBack.value) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
            }
            IconButton(onClick = { if (canForward.value) webView.goForward() }, enabled = canForward.value) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
            }
            IconButton(onClick = { webView.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { showParams.value = !showParams.value }) {
                Icon(
                    if (showParams.value) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (showParams.value) "收起参数" else "展开参数"
                )
            }
        }

        // ---- 参数区（地址栏/快捷芯片/说明条）：可收起，默认收起最大化浏览器 ----
        AnimatedVisibility(visible = showParams.value) {
            Column {
                // 地址输入行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    OutlinedTextField(
                        value = urlState.value,
                        onValueChange = { urlState.value = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("光猫地址") },
                        placeholder = { Text("http://192.168.1.1") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        var u = urlState.value.trim()
                        if (u.isNotEmpty()) {
                            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
                            webView.loadUrl(u)
                        }
                    }) {
                        Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("打开")
                    }
                }
                // 快捷地址芯片（横向可滚动，防溢出）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val quickAddrs = remember {
                        val gw = defaultGatewayIp(context)
                        val commons = listOf("192.168.1.1", "192.168.0.1", "192.168.100.1")
                        buildList {
                            gw?.let { add(it) }   // 网关置首（装维内网 192.168.10.x 网关很常见，不过滤）
                            commons.filter { it != gw }.forEach { add(it) }
                        }
                    }
                    quickAddrs.forEach { addr ->
                        AssistChip(
                            onClick = { webView.loadUrl("http://$addr") },
                            label = { Text(addr) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    if (loadingState.value) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 4.dp))
                    }
                }
                // 说明条：AI 联动 + 验证码 + 只读约束
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = "💡 默认地址已自动填当前网络网关（光猫管理地址）；AI 排障可读取本页内容帮你分析光猫信息（对话里说\"读取光猫页面\"）；登录验证码需手动输入；AI 只读页面、不会点击重启/恢复出厂等操作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                // 清除数据行（2026-08-18 用户需求：不同局域网网关 IP 可能相同但页面不同，
                // 缓存会把上一家光猫的旧页面带到下一家——提供清除缓存/登录态按钮，用户自己选）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    OutlinedButton(onClick = { clearWebData() }) {
                        Text("🧹 清除缓存与登录态（换光猫/页面异常时用）")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (clearedNotice.value) {
                        Text(
                            text = "已清除，正在重新加载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (loadFailed.value) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Text(
                    text = "⚠ 页面加载失败：请确认光猫地址正确、手机与光猫在同一网络（光猫管理页多为 http，需同网段访问）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // 网关自动切换提示（切网络后已自动切到新网关）
        gwNotice.value?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // ---- WebView ----
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
    }

    // 页面打开：动态读当前网关——首次加载默认地址；网络网关变化（WebView 复用还停在旧网关）自动切换
    LaunchedEffect(Unit) {
        delay(300)
        val currentGw = defaultGatewayIp(context) ?: "192.168.1.1"
        val cur = webView.url
        if (cur.isNullOrBlank()) {
            webView.loadUrl("http://$currentGw")
        } else if (!cur.contains(currentGw)) {
            // 【2026-08-18 用户反馈"不会自动切换网关地址"】切网络后网关变了：
            // WebView 复用导致还显示旧网关页面——自动切到当前网络网关并提示
            urlState.value = "http://$currentGw"
            webView.loadUrl("http://$currentGw")
            gwNotice.value = "网络网关已变化，已自动切换到 $currentGw"
            delay(5000)
            gwNotice.value = null
        }
    }
}

/** 动态读当前网络默认网关（免 root，光猫/路由管理地址通常就是网关；只取 IPv4，IPv6 link-local 网关无意义） */
private fun defaultGatewayIp(context: android.content.Context): String? {
    return runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        lp.routes.firstOrNull {
            it.hasGateway() && it.isDefaultRoute && it.gateway is java.net.Inet4Address
        }?.gateway?.hostAddress
    }.getOrNull()
}

/**
 * 全局持有一个光猫 WebView（跨 tab 复用）：切到 AI 助手时页面不销毁、后台继续跑
 * （JS 实时参数不中断），回到本页复用同一实例（内容/登录态保留），AI 读实时页面。
 * 2026-08-18 用户反馈"切 AI 页网页挂起、很多实时参数没加载"——原实现切 tab 销毁 WebView，
 * 改为全局持有；重新进入时把 WebView 从旧视图树摘下重新挂载。
 */
object ModemWebHolder {
    @Volatile
    private var holder: WebView? = null

    /** 取复用实例；无则新建并持有（复用前从旧父视图摘除，避免"已有 parent"） */
    fun acquire(context: android.content.Context): WebView {
        holder?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            return wv
        }
        return WebView(context).also { holder = it }
    }
}
