package com.installer.toolbox.core.agent.support

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 光猫 Web 助手桥（需求 2026-08-17 登记）：App『光猫 Web 助手』页面把内嵌 WebView 注册到本桥，
 * AI 的 web_page_read 工具经它**同进程**读取当前页面 DOM（标题/网址/正文纯文本），
 * 绕开光猫登录页加密 JS 与验证码（验证码仍需用户手动输入一次）。
 * 约束：AI 只读页面内容做分析，不点击任何按钮（重启/恢复出厂等危险操作不在 AI 范围内）。
 *
 * 生命周期：底部 tab 切换会销毁光猫页 WebView（popUpTo+saveState）——销毁前抓一次 DOM 快照，
 * AI 在页面离开后读快照（登录后设备信息等静态内容足够分析）；重新进入页面会重新加载。
 */
object WebPageBridge {

    @Volatile
    private var current: WebView? = null

    /** 页面销毁前抓取的 DOM 快照（JSON 原文；无 live WebView 时 AI 读它） */
    @Volatile
    private var snapshot: String? = null

    /** 页面打开时注册（供 AI 读取） */
    fun register(webView: WebView) {
        current = webView
        snapshot = null   // 新页面接管，清掉旧快照
    }

    /** 页面销毁：抓快照 → 注销 → 延迟销毁（等 JS 回调写完快照再 destroy） */
    fun detachWithSnapshot(webView: WebView) {
        val h = Handler(Looper.getMainLooper())
        h.post {
            try {
                webView.evaluateJavascript(DUMP_JS) { json -> snapshot = json }
            } catch (e: Exception) {
                // 页面已不可读，保留旧快照
            }
            h.postDelayed({
                if (current === webView) current = null
                runCatching { webView.destroy() }
            }, 1500)
        }
    }

    /** 当前是否可读取（有 live WebView 或已有快照） */
    fun isAvailable(): Boolean = current != null || snapshot != null

    /** 提取 DOM 的 JS：标题/网址/正文纯文本 + 加载状态（readyState 供读取前等待判断）+ 背景诊断 */
    private val DUMP_JS = """
        (function(){
          var b = document.body;
          var cs = b ? getComputedStyle(b) : null;
          var mb = document.querySelector('.main-bg, #main-bg, .login-page');
          var mbcs = mb ? getComputedStyle(mb) : null;
          var t = b ? b.innerText : '';
          t = t.replace(/\u0000/g, '').replace(/\n{3,}/g, '\n\n').trim();
          return JSON.stringify({
            title: document.title || '',
            url: location.href || '',
            readyState: document.readyState || '',
            text: t.slice(0, 20000),
            cssLoaded: !!(document.styleSheets && document.styleSheets.length),
            bodyBgImage: cs ? cs.backgroundImage : '',
            bodyBgColor: cs ? cs.backgroundColor : '',
            mainBgImage: mbcs ? mbcs.backgroundImage : '',
            imgLoaded: (function(){var im=document.querySelector('.main-bg');if(!im)return '';var u=im.currentStyle||getComputedStyle(im);return u.backgroundImage||'';})()
          });
        })()
    """.trimIndent()

    /**
     * 读取当前 WebView 的 DOM（挂起函数）。evaluateJavascript 必须在主线程调用且回调也在主线程，
     * 这里用主线程 Handler + suspendCancellableCoroutine 桥接回调用协程。
     *
     * 【2026-08-18 用户反馈"光猫加载慢 AI 会不会误读"】读取前**等待页面加载完成**
     * （document.readyState=complete，最多等 3 秒、每 600ms 检查）——光猫/路由管理页多为
     * JS 异步加载，中途读 DOM 数据残缺会让 AI 误判；超时仍加载中则返回当前快照，
     * 由调用方据 readyState 标注提示（AI 应告知用户页面未加载完/稍后重读）。
     *
     * @return JS 返回的原始 JSON 字符串（含转义）；页面已离开时返回快照；都无则 null
     */
    suspend fun readDom(): String? {
        val wv = current
        if (wv != null) {
            var lastRaw: String? = null
            repeat(6) {   // 最多等 3 秒（6 × 600ms），页面加载完成即提前返回
                val raw = readOnce(wv) ?: return null
                lastRaw = raw
                // readyState==complete 视为加载完成（deepseek 风格 JSON 字段在 raw 里，直接子串判断）
                if (raw.contains("\"complete\"")) return raw
                kotlinx.coroutines.delay(600)
            }
            return lastRaw
        }
        return snapshot
    }

    /** 单次 evaluateJavascript 读取（主线程桥接） */
    private suspend fun readOnce(wv: WebView): String? = suspendCancellableCoroutine { cont ->
        val h = Handler(Looper.getMainLooper())
        h.post {
            if (cont.isCancelled) return@post
            try {
                wv.evaluateJavascript(DUMP_JS) { json ->
                    if (!cont.isCancelled) {
                        snapshot = json   // 顺带更新快照（下次离开前已有最新）
                        cont.resume(json)
                    }
                }
            } catch (e: Exception) {
                if (!cont.isCancelled) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { h.removeCallbacksAndMessages(null) }
    }

    /**
     * 在页面执行一段 JS（web_page_action 用：点击/输入/提交），返回 JS 返回值原文。
     * @return null=没有 live 页面或执行失败
     */
    suspend fun executeJs(js: String): String? {
        val wv = current ?: return null
        return suspendCancellableCoroutine { cont ->
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (cont.isCancelled) return@post
                try {
                    wv.evaluateJavascript(js) { json ->
                        if (!cont.isCancelled) cont.resume(json)
                    }
                } catch (e: Exception) {
                    if (!cont.isCancelled) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { h.removeCallbacksAndMessages(null) }
        }
    }
}
