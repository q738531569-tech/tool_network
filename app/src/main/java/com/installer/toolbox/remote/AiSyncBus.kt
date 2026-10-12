package com.installer.toolbox.remote

/**
 * 三端实时同步事件桥（同进程单例）：PDA 屏 AI 页 ↔ 远程控制服务 ↔ 控制端。
 *
 * - 本地轮次（PDA 屏 AI 页发起）：AiViewModel 发布 → RemoteControlService 监听转
 *   ai_event / ai_done / approval_req 帧给控制端（控制端实时看到本地聊天过程）；
 * - 远程轮次（控制端发起）：RemoteAiSession 发布 → AiViewModel 监听刷新本地 UI
 *   （PDA 屏实时看到控制端聊天过程；sessionId 不匹配时忽略，切会话重载兜底）；
 * - 控制端 stop 帧 → Service 按 localBusy 路由：本地轮次 → bus stop 事件；远程轮次 → 原逻辑；
 * - 本地轮次的 root_shell 审批（远程开启时本地弹窗静默）→ bus approval 事件 → Service 转
 *   approval_req → 控制端 approve 帧 → bus approval_resp 事件 → AiViewModel 放行/拒绝。
 */
object AiSyncBus {

    /** 事件类型：user_msg 对方发起一轮 / reasoning·tool_log·tool_result·status·usage 流式过程 /
     *  done 一轮完成 / approval·approval_resp 本地轮次审批桥接 / stop 控制端停止本地轮次。
     *  source：local=本地轮次（PDA 屏发起，Service 转帧给控制端）/ remote=远程轮次（控制端发起，
     *  只同步给 PDA 屏本地 UI）——按来源路由各收各的，防止事件回环转发产生重复帧 */
    data class Event(
        val kind: String,
        val source: String = "local",
        val sessionId: String = "",
        val text: String = "",
        val name: String = "",
        val input: String = "",
        val output: String = "",
        val cacheHitTokens: Long = 0,
        val cacheMissTokens: Long = 0,
        val roundSec: Long = 0,
        val userTs: Long = 0,
        val error: String = "",
        val reqId: String = "",
        val command: String = "",
        val allow: Boolean = false
    )

    /** 本地轮次（PDA 屏发起）是否进行中：Service 收到 stop 帧时据此路由 */
    @Volatile
    var localBusy: Boolean = false
        private set

    /** 远程轮次（控制端发起）是否进行中：本地 AiViewModel 发消息前据此互斥（防两轮并发抢工具输出） */
    @Volatile
    var remoteBusy: Boolean = false
        private set

    /** PDA 屏 AI 页监听（接收远程轮次事件实时刷新 UI；AiViewModel init 注册、onCleared 注销） */
    @Volatile
    var localListener: ((Event) -> Unit)? = null

    /** 远程控制服务监听（接收本地轮次事件转帧外发；Service onCreate 注册） */
    @Volatile
    var remoteListener: ((Event) -> Unit)? = null

    /** 发布事件：按来源路由——本地轮次事件只给远程监听（Service 转帧外发）；
     *  远程轮次事件只给本地监听（刷新 PDA 屏 UI）。防止回环转发产生重复帧。 */
    fun publish(e: Event) {
        if (e.source == "local") {
            remoteListener?.invoke(e)
        } else {
            localListener?.invoke(e)
        }
    }

    /** 本地轮次开始/结束标志（AiViewModel 维护；Service 读） */
    fun setLocalBusy(v: Boolean) {
        localBusy = v
    }

    /** 远程轮次开始/结束标志（RemoteAiSession 维护；AiViewModel 读） */
    fun setRemoteBusy(v: Boolean) {
        remoteBusy = v
    }
}
