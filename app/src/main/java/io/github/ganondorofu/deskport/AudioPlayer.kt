package io.github.ganondorofu.deskport

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PC の音を鳴らす。ホストが送る 16bit PCM(ステレオ)を、そのまま AudioTrack へ流す。
 *  - 音量はここで調整する(PC の音量は変えない)。端末のメディア音量とは、掛け合わせになる。
 *  - 溜まりすぎたら、古いものを捨てて、遅れを増やさない。
 *  - 複数の配信から同じ音が届いても(一括表示)、先に届いた配信の分だけを鳴らす。
 */
object AudioPlayer {
    @Volatile var volume = 1f
        set(v) { field = v.coerceIn(0f, 1f); track?.setVolume(field) }

    /** 診断用: 届いたパケットの累計と、最後に届いた時刻。 */
    val received = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var lastAt = 0L
    val isPlaying: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING
    fun routedName(): String = try { track?.routedDevice?.let { "${it.productName} (type ${it.type})" } ?: "-" } catch (_: Exception) { "-" }

    // ---- Opus(圧縮された音)。ホストが Opus で送ってきたときに、MediaCodec で、PCM に戻す
    /** この端末が、Opus を復号できるか(できれば、接続のときに、ホストに、Opus で送ってくれと頼む)。 */
    val opusSupported: Boolean by lazy {
        try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals("audio/opus", true) } }
        } catch (_: Exception) { false }
    }
    private val opusQueue = ArrayBlockingQueue<Pair<Int, ByteArray>>(48)
    private var opusThread: Thread? = null
    @Volatile private var opusRunning = false

    /** Opus のフレーム(10ms)が届いた。 */
    fun feedOpus(src: Long, seq: Int, data: ByteArray) {
        val now = System.currentTimeMillis()
        received.incrementAndGet(); lastAt = now
        synchronized(this) {
            if (!claim(src, now)) return
            val s = seq.toLong() and 0xFFFFFFFFL
            if (lastSeq >= 0 && ((s - lastSeq) and 0xFFFFFFFFL).toInt() <= 0) return
            lastSeq = s
            if (!opusRunning) startOpus()
            while (!opusQueue.offer(seq to data)) opusQueue.poll()
        }
    }

    /**
     * 音の持ち主を決める(synchronized の中で呼ぶ)。持ち主でない配信は、持ち主が鳴らしている間は無視する。
     * ただし、新しい接続(番号が大きい)は、持ち主を奪える。ディスプレイを切り替えるとき、新しい配信の音が来た瞬間に、無音なしで入れ替わる。
     * 持ち主が変わるときは、古い接続の溜まりを捨てる(再接続のあとに、前の接続の音が鳴らないように)。
     */
    private fun claim(src: Long, now: Long): Boolean {
        if (owner == src) { ownerAt = now; return true }
        if (owner != -1L && src < owner && now - ownerAt < 700) return false
        owner = src; ownerAt = now; lastSeq = -1
        opusQueue.clear(); queue.clear()
        return true
    }

    /** いまの持ち主(いなければ -1)。 */
    fun currentOwner(): Long = synchronized(this) { if (owner != -1L && System.currentTimeMillis() - ownerAt < 700) owner else -1L }

    private fun startOpus() {
        try {
            val preSkip = 312   // libopus の符号化の先読み(48kHz のサンプル数)
            val fmt = MediaFormat.createAudioFormat("audio/opus", 48000, 2)
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray()).put(1).put(2).putShort(preSkip.toShort()).putInt(48000).putShort(0).put(0).array()
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(head))
            fmt.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(preSkip * 1_000_000_000L / 48000).also { it.flip() })
            fmt.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(80_000_000L).also { it.flip() })
            val c = MediaCodec.createDecoderByType("audio/opus")
            c.configure(fmt, null, null, 0)
            c.start()
            opusRunning = true
            opusThread = Thread({ decodeLoop(c) }, "opus-dec").also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            Log.w("AudioPlayer", "Opus の復号を始められません: ${e.message}")
        }
    }

    private fun decodeLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (opusRunning) {
                val p = opusQueue.poll(100, TimeUnit.MILLISECONDS)
                if (p != null) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        b.clear(); b.put(p.second)
                        c.queueInputBuffer(i, 0, p.second.size, p.first * 10_000L, 0)
                    }
                }
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val ob = c.getOutputBuffer(o)!!
                    val out = ByteArray(info.size)
                    ob.position(info.offset); ob.get(out, 0, info.size)
                    c.releaseOutputBuffer(o, false)
                    if (out.isNotEmpty()) {
                        synchronized(this) { if (track == null || rate != 48000) open(48000) }
                        while (queue.size > 12) queue.poll()   // 溜まりすぎない(120ms まで)
                        queue.offer(out)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { c.stop() } catch (_: Exception) {}
            c.release()
        }
    }

    private fun stopOpus() {
        opusRunning = false
        opusThread?.interrupt(); opusThread = null
        opusQueue.clear()
    }

    private val queue = ArrayBlockingQueue<ByteArray>(24)   // 5ms x 24 = 120ms まで
    private var track: AudioTrack? = null
    private var rate = 0
    private var thread: Thread? = null
    @Volatile private var running = false
    private var owner = -1L
    private var ownerAt = 0L
    private var lastSeq = -1L

    /** src=どの配信から届いたか(ディスプレイ番号)。 */
    fun feed(src: Long, seq: Int, sampleRate: Int, pcm: ByteArray) {
        val now = System.currentTimeMillis()
        received.incrementAndGet(); lastAt = now
        synchronized(this) {
            if (!claim(src, now)) return
            val s = seq.toLong() and 0xFFFFFFFFL
            if (lastSeq >= 0 && ((s - lastSeq) and 0xFFFFFFFFL).toInt() <= 0) return   // 古い・重複
            lastSeq = s
            if (track == null || rate != sampleRate) open(sampleRate)
            while (!queue.offer(pcm)) queue.poll()
        }
    }

    private fun open(sampleRate: Int) {
        close()
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(min * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume)
        t.play()
        track = t; rate = sampleRate
        running = true
        thread = Thread({
            while (running) {
                val b = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try { t.write(b, 0, b.size) } catch (_: Exception) { break }
            }
        }, "audio-out").also { it.isDaemon = true; it.start() }
    }

    private fun close() {
        running = false
        thread?.interrupt(); thread = null
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null
        queue.clear()
    }

    /** この接続が終わった。音の持ち主だったなら手放し、その接続の溜まりを捨てる(ほかの接続の音を、すぐ鳴らせるように)。 */
    fun release(src: Long) = synchronized(this) {
        if (owner == src) { owner = -1L; lastSeq = -1; opusQueue.clear(); queue.clear() }
    }

    /** 配信を止めたとき。 */
    fun stop() = synchronized(this) { stopOpus(); close(); owner = -1L; lastSeq = -1 }
}
