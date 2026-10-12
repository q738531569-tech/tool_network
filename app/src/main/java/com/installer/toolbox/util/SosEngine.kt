package com.installer.toolbox.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * SOS 紧急求救引擎（免 root）：
 * - 声光摩斯：手电筒闪烁 + 蜂鸣，循环发送 SOS（···---···），页面关闭自动停；
 * - 定位：GPS 优先、网络回退的单次定位（经纬度 + 高德地图链接）；
 * - 短信求救：向预填紧急联系人群发含位置信息的求救短信（SEND_SMS 权限）；
 * - 紧急联系人：最多 3 个号码，SharedPreferences 预存。
 *
 * 注意：短信/拨号是真实动作，调用前必须由用户点击大按钮明确触发。
 */
object SosEngine {

    private const val PREFS = "sos"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_EXTRA = "extra_info"
    private const val KEY_SMS_TEMPLATE = "sms_template"

    /** 默认短信模板：{位置} 替换为定位结果、{附加} 替换为附加信息（空则省略该句） */
    const val DEFAULT_SMS_TEMPLATE =
        "【SOS 紧急求救】我遇到了紧急情况，请尽快联系我或帮我报警！当前位置：{位置}。附加信息：{附加}。（来自装维通一键求救）"

    // 摩斯 SOS：点 200ms / 划 600ms，字符间 200ms，字母间 600ms，轮间 2s，
    // 持续循环直到用户手动停止（无轮数上限）
    private const val DOT_MS = 200L
    private const val DASH_MS = 600L
    private const val GAP_MS = 200L
    private const val LETTER_GAP_MS = 600L

    /** 摩斯运行状态（UI 显示/停止用） */
    @Volatile
    var morseRunning = false
        private set

    /** 紧急联系人（逗号分隔存 prefs） */
    fun contacts(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONTACTS, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.matches(Regex("""1\d{10}""")) || it.matches(Regex("""\d{7,12}""")) }
            .distinct()

    /** 保存紧急联系人（逗号分隔字符串，最多 3 个） */
    fun saveContacts(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONTACTS, list.take(3).joinToString(","))
            .apply()
    }

    /** 自定义附加信息（血型/病史等，随短信发出） */
    fun extraInfo(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXTRA, "").orEmpty()

    fun saveExtraInfo(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXTRA, text.take(100)).apply()
    }

    /** 短信模板（用户可编辑；默认 [DEFAULT_SMS_TEMPLATE]） */
    fun smsTemplate(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SMS_TEMPLATE, null) ?: DEFAULT_SMS_TEMPLATE

    fun saveSmsTemplate(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SMS_TEMPLATE, text.take(300).ifBlank { DEFAULT_SMS_TEMPLATE })
            .apply()
    }

    /** 组装求救短信：模板占位符替换（{位置}→定位文本；{附加}→附加信息，空则省略附加句） */
    fun buildSmsText(template: String, loc: LocationResult, extra: String): String {
        var t = template.replace("{位置}", loc.toText())
        if (extra.isBlank()) {
            // 附加为空：移除"附加信息：{附加}。"整句（含前后空白）
            t = t.replace(Regex("""\s*附加信息：\{附加\}。"""), "")
            t = t.replace("{附加}", "")
        } else {
            t = t.replace("{附加}", extra)
        }
        return t
    }

    /**
     * 声光摩斯 SOS：手电闪烁 + 蜂鸣，**持续循环直到手动停止**（后台线程执行，立即返回）；
     * 再次调用停止。返回 true=已启动；false=已停止。
     */
    fun toggleMorse(context: Context): Boolean {
        if (morseRunning) {
            morseRunning = false
            stopTorch(context)
            return false
        }
        morseRunning = true
        Thread {
            try {
                runBlocking { morseLoop(context) }
            } catch (e: Exception) {
                // 循环异常不崩线程
            } finally {
                morseRunning = false
                stopTorch(context)
            }
        }.start()
        return true
    }

    /** 摩斯循环（后台线程内运行）：持续循环直到 morseRunning=false */
    private suspend fun morseLoop(context: Context) {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val torchId = runCatching {
            cam.cameraIdList.firstOrNull { id ->
                cam.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        // 高穿透蜂鸣：AudioTrack 高频方波 + 闹钟流音量拉满（失败回退 ToneGenerator）
        val beeper = SosBeeper.start(context)
        try {
            while (morseRunning) {
                repeat(3) { if (!flashDot(torchId, cam, beeper)) return }
                delay(LETTER_GAP_MS)
                repeat(3) { if (!flashDash(torchId, cam, beeper)) return }
                delay(LETTER_GAP_MS)
                repeat(3) { if (!flashDot(torchId, cam, beeper)) return }
                delay(2000)
            }
        } finally {
            runCatching { if (torchId != null) cam.setTorchMode(torchId, false) }
            beeper?.stop()
        }
    }

    /** 关闭全部手电（停止/退出兜底） */
    private fun stopTorch(context: Context) {
        runCatching {
            val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cam.cameraIdList.forEach { runCatching { cam.setTorchMode(it, false) } }
        }
    }

    /** 单次闪光+蜂鸣（点长度）；返回 false=被手动停止 */
    private suspend fun flashDot(torchId: String?, cam: CameraManager, beeper: SosBeeper?): Boolean =
        flash(torchId, cam, beeper, DOT_MS)

    private suspend fun flashDash(torchId: String?, cam: CameraManager, beeper: SosBeeper?): Boolean =
        flash(torchId, cam, beeper, DASH_MS)

    private suspend fun flash(torchId: String?, cam: CameraManager, beeper: SosBeeper?, ms: Long): Boolean {
        if (!morseRunning) return false
        runCatching { if (torchId != null) cam.setTorchMode(torchId, true) }
        beeper?.on()
        delay(ms)
        runCatching { if (torchId != null) cam.setTorchMode(torchId, false) }
        beeper?.off()
        delay(GAP_MS)
        return true
    }

    /**
     * 高穿透蜂鸣器：AudioTrack 合成 2.4kHz 方波（人耳最敏感频段），
     * 闹钟音频流音量临时拉满（结束自动还原）；AudioTrack 失败回退 ToneGenerator。
     */
    private class SosBeeper(
        private val track: AudioTrack?,
        private val tone: ToneGenerator?,
        private val audioManager: AudioManager?,
        private val originalVolume: Int
    ) {
        fun on() {
            runCatching {
                track?.let {
                    if (it.playState != AudioTrack.PLAYSTATE_PLAYING) it.play()
                }
            }
            tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 800)
        }

        fun off() {
            runCatching {
                track?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                }
            }
            tone?.stopTone()
        }

        fun stop() {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { tone?.release() }
            // 还原闹钟音量
            audioManager?.let { am ->
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0) }
            }
        }

        companion object {
            fun start(context: Context): SosBeeper? {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val original = am.getStreamVolume(AudioManager.STREAM_ALARM)
                // 拉满闹钟音量（求救场景；stop 时还原）
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
                val track = buildTrack()
                if (track != null) return SosBeeper(track, null, am, original)
                val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
                return if (tone != null) SosBeeper(null, tone, am, original) else {
                    runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, original, 0) }
                    null
                }
            }

            /** 合成 2.4kHz 方波（MODE_STATIC 循环播放，on/off 即响/停） */
            private fun buildTrack(): AudioTrack? = try {
                val sampleRate = 44100
                val durationMs = 600
                val samples = sampleRate * durationMs / 1000
                val freq = 2400.0
                val buffer = ShortArray(samples)
                for (i in buffer.indices) {
                    val v = if (sin(2.0 * Math.PI * freq * i / sampleRate) > 0) 24000.0 else -24000.0
                    buffer[i] = v.toInt().toShort()
                }
                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    buffer.size * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(buffer, 0, buffer.size)
                track.setLoopPoints(0, samples, -1)
                track.setVolume(1f)
                track
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 停止摩斯（页面退出兜底） */
    suspend fun stopMorse(context: Context) {
        if (!morseRunning) return
        morseRunning = false
        withContext(Dispatchers.IO) {
            runCatching {
                val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cam.cameraIdList.forEach { runCatching { cam.setTorchMode(it, false) } }
            }
        }
    }

    /** 定位结果（成功 = lat/lng/链接；失败 = null + 原因） */
    data class LocationResult(
        val latitude: Double?,
        val longitude: Double?,
        val mapUrl: String?,
        val error: String?
    ) {
        fun toText(): String = if (latitude != null && longitude != null) {
            // Locale.US：坐标小数必须用点号（部分语言环境默认逗号会破坏经纬度格式）
            "纬度 ${String.format(java.util.Locale.US, "%.5f", latitude)}，经度 ${String.format(java.util.Locale.US, "%.5f", longitude)}（地图：$mapUrl）"
        } else {
            "未能获取定位（$error）"
        }
    }

    /**
     * 单次定位：GPS 优先 6 秒，超时/不可用回退网络定位 4 秒，仍失败取最近已知位置，
     * 都没有则返回失败（短信内容降级注明）。未授权定位权限时直接返回失败说明。
     */
    @SuppressLint("MissingPermission") // 函数开头已显式检查权限，未授权直接返回
    suspend fun getLocation(context: Context): LocationResult = withContext(Dispatchers.IO) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // 显式权限检查：未授权定位时绝不调用定位 API（lint 要求 + 防 SecurityException）
        val locGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!locGranted) {
            return@withContext LocationResult(null, null, null, "未授权定位权限（可在系统设置或 SOS 页授权）")
        }
        suspend fun listen(provider: String, timeoutMs: Long): Location? {
            var got: Location? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) { got = loc }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            return try {
                val looper = Looper.myLooper() ?: Looper.getMainLooper()
                if (!lm.isProviderEnabled(provider)) {
                    // 用主线程 looper 时 requestSingleUpdate 有兼容问题，用 null looper 会立即失败；
                    // 直接轮询已知位置兜底
                    null
                } else {
                    lm.requestSingleUpdate(provider, listener, looper)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (got == null && System.currentTimeMillis() < deadline) {
                        delay(200)
                    }
                    got
                }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { lm.removeUpdates(listener) }
            }
        }

        val gps = listen(LocationManager.GPS_PROVIDER, 6000)
        val loc = gps ?: listen(LocationManager.NETWORK_PROVIDER, 4000)
            ?: runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()

        if (loc == null) {
            LocationResult(null, null, null, "GPS/网络定位均不可用（可能未授权定位或室内无信号）")
        } else {
            val lat = loc.latitude
            val lng = loc.longitude
            val url = "https://uri.amap.com/marker?position=$lng,$lat&name=%E6%B1%82%E6%95%91%E4%BD%8D%E7%BD%AE"
            LocationResult(lat, lng, url, null)
        }
    }

    /**
     * 向全部紧急联系人发送求救短信（内容含位置与附加信息）。
     * @return 每个号码的发送结果（号码 → 成功/失败原因）
     */
    suspend fun sendSosSms(
        context: Context,
        extra: String = extraInfo(context)
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val list = contacts(context)
        if (list.isEmpty()) {
            return@withContext mapOf("" to "未设置紧急联系人")
        }
        val loc = getLocation(context)
        val text = buildSmsText(smsTemplate(context), loc, extra)
        val result = LinkedHashMap<String, String>()
        try {
            val sms = SmsManager.getDefault()
            list.forEach { phone ->
                result[phone] = try {
                    sms.sendTextMessage(phone, null, text, null, null)
                    "已发送"
                } catch (e: Exception) {
                    "发送失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        } catch (e: Exception) {
            list.forEach { result[it] = "发送失败：${e.message ?: e.javaClass.simpleName}" }
        }
        result
    }
}
