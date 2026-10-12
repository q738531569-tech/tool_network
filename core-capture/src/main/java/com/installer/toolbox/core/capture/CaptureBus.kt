package com.installer.toolbox.core.capture

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 抓包事件总线：PacketCaptureService 抓包线程 → UI（ViewModel/Compose）的单向数据通道。
 *
 * 使用 [MutableSharedFlow]：
 * - 无 replay（UI 只需实时快照，不需要补历史）；
 * - extraBufferCapacity = 64，且满时丢弃最旧快照（DROP_OLDEST），
 *   保证 UI 消费慢时不会阻塞抓包线程（tryEmit 非挂起）。
 */
object CaptureBus {

    private const val BUFFER_CAPACITY = 64

    private val _flow = MutableSharedFlow<TrafficSnapshot>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** UI 订阅入口：collect 到每个统计周期的 [TrafficSnapshot]（含风暴状态） */
    val flow: SharedFlow<TrafficSnapshot> = _flow.asSharedFlow()

    /** 由 PacketCaptureService 调用，推送一个统计快照（非挂起、线程安全） */
    fun emit(snapshot: TrafficSnapshot) {
        _flow.tryEmit(snapshot)
    }
}
