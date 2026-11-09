package io.github.ganondorofu.deskport

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

/**
 * 受信した映像(H.264/HEVC/AV1)を、MediaCodec で復号して Surface に描く。
 *
 * 遅延を減らすため、低遅延モード(KEY_LOW_LATENCY)とリアルタイム優先度を指定し、
 * 復号できた画像は待たずにすぐ表示する。SPS/PPS(符号化の設定)は映像の中に含まれて届くので、
 * 別に渡さず、そのまま入力する。
 */
class VideoDecoder {
    @Volatile private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var outThread: Thread? = null
    @Volatile private var running = false

    /** 復号して表示したフレーム数、入力した/捨てたフレーム数(状況の表示用)。 */
    val framesShown = AtomicLong(0)
    val framesIn = AtomicLong(0)
    val framesDropped = AtomicLong(0)
    @Volatile var mime: String = ""
    @Volatile var width = 0
    @Volatile var height = 0

    /** 画面が無い間(アプリが裏にいるとき等)の出力先。これがあるので、画面が消えても復号は止めずに続けられる。 */
    // SurfaceTexture を保持しておく(参照が無いと回収されて Surface が無効になり、復号器が壊れる)
    private val dummyTex by lazy { android.graphics.SurfaceTexture(false) }
    private val dummy by lazy { Surface(dummyTex) }
    /** setup から stop までの間だけ true。止めている最中・止めた後に、作り直してしまわないための印。 */
    @Volatile private var active = false
    private var lastFormat = 0; private var lastW = 0; private var lastH = 0; private var lastFps = 0

    /** 表示先の画面を付け替える。null なら、画面が無い状態(復号は続ける)。 */
    @Synchronized
    fun attachSurface(s: Surface?) {
        val old = surface
        surface = s
        var ok = true
        try { codec?.setOutputSurface(s ?: dummy) } catch (e: Exception) { Log.w(TAG, "setOutputSurface: ${e.message}"); ok = false }
        if (old != null && old !== s) try { old.release() } catch (_: Exception) {}
        if (!ok && active && codec != null) rebuild()
    }

    /** ネイティブから: 映像の形式が決まったときに呼ばれる。0=成功。 */
    @Synchronized
    fun setup(format: Int, w: Int, h: Int, fps: Int): Int {
        try { codec?.release() } catch (_: Exception) {}   // 前の配信の残りがあれば片付ける
        codec = null
        lastFormat = format; lastW = w; lastH = h; lastFps = fps
        val m = when {
            format and 0x000F != 0 -> "video/avc"
            format and 0x0F00 != 0 -> "video/hevc"
            format and 0xF000 != 0 -> "video/av01"
            else -> return -1
        }
        // 画面が無くても始める(後から付け替えられる)。無効になった面(破棄済み)は使わない
        val s = surface?.takeIf { it.isValid } ?: dummy
        return try {
            val fmt = MediaFormat.createVideoFormat(m, w, h).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)          // 0=リアルタイム
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            }
            val c = MediaCodec.createDecoderByType(m)
            c.configure(fmt, s, null, 0)
            codec = c; mime = m; width = w; height = h; active = true
            Log.i(TAG, "decoder ${c.name} $m ${w}x$h@$fps")
            0
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e); -3
        }
    }

    @Synchronized
    fun start() {
        val c = codec ?: return
        c.start()
        running = true
        outThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (running) {
                try {
                    val i = c.dequeueOutputBuffer(info, 10_000)
                    if (i >= 0) { val show = surface != null; c.releaseOutputBuffer(i, show); if (show) framesShown.incrementAndGet() }   // 待たずにすぐ表示
                } catch (e: Exception) { if (running) Log.w(TAG, "output: ${e.message}"); break }
            }
        }, "decoder-output").also { it.start() }
    }

    /** ネイティブから: 1フレーム分のデータ。復号に渡す。 */
    fun submit(data: ByteArray, length: Int, frameNumber: Int, frameType: Int): Int {
        val c = codec ?: return DR_OK
        return try {
            val idx = c.dequeueInputBuffer(5_000)           // 最大5msだけ待つ
            if (idx < 0) { framesDropped.incrementAndGet(); return DR_NEED_IDR }   // 詰まったら捨てて、次のキーフレームを要求
            val buf = c.getInputBuffer(idx) ?: return DR_NEED_IDR
            buf.clear(); buf.put(data, 0, length)
            val flags = if (frameType == 1) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(idx, 0, length, System.nanoTime() / 1000, flags)
            framesIn.incrementAndGet()
            DR_OK
        } catch (e: Exception) {
            Log.w(TAG, "submit: ${e.message}")
            if (e is IllegalStateException && active && codec === c) rebuild()
            DR_NEED_IDR
        }
    }

    /** 復号器が壊れたとき(画面の付け替えに失敗した等)に作り直す。映像は次のキーフレームから復活する。 */
    @Synchronized
    private fun rebuild() {
        if (!active) return
        Log.w(TAG, "rebuild decoder")
        running = false
        outThread?.join(500); outThread = null
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        if (setup(lastFormat, lastW, lastH, lastFps) == 0) start()
    }

    @Synchronized
    fun stop() {
        active = false
        running = false
        outThread?.join(500)
        outThread = null
        try { codec?.stop() } catch (_: Exception) {}
    }

    @Synchronized
    fun cleanup() {
        active = false
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    companion object { private const val TAG = "VideoDecoder" }
}

/** submit の戻り値。 */
const val DR_OK = 0
const val DR_NEED_IDR = -1
/** setup の映像形式(HEVC)。 */
const val VIDEO_FORMAT_H265 = 0x0100
