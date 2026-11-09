package io.github.ganondorofu.deskport

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 自作ホスト(host/ の deskport-host)との通信。形式は host/src/serve.rs の冒頭を参照。
 *  - 全パケットを AES-256-GCM で暗号化する(鍵 = SHA-256(鍵)。形式は host/src/crypto.rs)。復号できないものは捨てる。
 *  - 映像: 接続要求を送り、映像情報を受けたらデコーダを用意して、映像データを組み立てて渡す。
 *    1フレームが欠けたら捨てて、キーフレームを要求する(キーフレームが来るまで、続きのフレームは捨てる)。
 *  - カーソル: 映像には写らないので、ホストが送る位置と形を受けて渡す。形は番号で保存し、持っていなければ再送を頼む。
 *  - 操作: マウス・キー・文字を送る(ボタンとキーは取りこぼしに備えて2回送る)。
 */
class NativeReceiver(
    private val address: String,
    private val port: Int,
    private val token: String,
    private val display: Int,
    private val kbps: Int,
    private val audio: Boolean,
    private val decoder: VideoDecoder,
    private val onStarted: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val onCursor: (x: Int, y: Int, visible: Boolean, shape: Int) -> Unit,
) {
    /** この接続の番号(大きいほど新しい)。音の持ち主を決めるのに使う。 */
    val audioId: Long = nextId.incrementAndGet()
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val lock = Any()
    private var seq = 0
    private val shapeParts = HashMap<Int, Array<ByteArray?>>()   // 組み立て中のカーソルの形(受信スレッドだけが触る)
    private val shapeAsked = HashMap<Int, Long>()
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()), "AES")
    private val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val recvCipher = Cipher.getInstance("AES/GCM/NoPadding")   // 受信スレッドだけが使う
    private val connId = ByteArray(7).also { SecureRandom().nextBytes(it) }   // 接続ごとのランダムな番号(ナンスの重複を避ける)
    private var counter = 0L

    fun start() {
        running = true
        thread = Thread({ run() }, "native-receiver").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        send(0x13, ByteArray(0), twice = false)
        try { socket?.close() } catch (_: Exception) {}
        thread?.join(1000)
        thread = null
        decoder.stop(); decoder.cleanup()
        AudioPlayer.release(audioId)   // 鳴らす音の持ち主が、この配信なら、すぐ、ほかの配信に譲る
    }

    // ---- 送信(認証つき) ----
    private fun send(type: Int, payload: ByteArray, twice: Boolean = false) {
        val sock = socket ?: return
        synchronized(lock) {
            seq++
            val body = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.LITTLE_ENDIAN).put(type.toByte()).putInt(seq).put(payload).array()
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(0).put(connId).putInt(counter.toInt()).array()   // 方向0=端末→ホスト
            // カウンタが一周する前に、番号を引き直す(同じナンスを二度使わない)
            if (counter == 0xFFFFFFFFL) { SecureRandom().nextBytes(connId); counter = 0 } else counter++
            val d = try {
                sendCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                nonce + sendCipher.doFinal(body)
            } catch (_: Exception) { return }
            try {
                sock.send(DatagramPacket(d, d.size))
                if (twice) sock.send(DatagramPacket(d, d.size))
            } catch (_: Exception) {}
        }
    }

    private fun le(vararg v: Int, sizes: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(sizes.sum()).order(ByteOrder.LITTLE_ENDIAN)
        v.forEachIndexed { i, x -> when (sizes[i]) { 1 -> bb.put(x.toByte()); 2 -> bb.putShort(x.toShort()); 4 -> bb.putInt(x) } }
        return bb.array()
    }

    // ---- 操作 ----
    fun mouseRel(dx: Int, dy: Int) = send(0x20, le(dx.coerceIn(-32768, 32767), dy.coerceIn(-32768, 32767), sizes = intArrayOf(2, 2)))
    fun mouseAbs(x: Int, y: Int) = send(0x21, le(x, y, sizes = intArrayOf(2, 2)))
    fun button(btn: Int, down: Boolean) = send(0x22, le(btn, if (down) 1 else 0, sizes = intArrayOf(1, 1)), twice = true)
    /**
     * スマホのカメラの1枚(JPEG)を、ホストへ送る(PC の仮想カメラになる)。1100バイトずつに分ける。
     * 形式: 0x30 [frame:u32][index:u16][count:u16][flags:u8(下位2ビット=回転 0/90/180/270)][JPEG の断片]
     */
    fun camera(jpeg: ByteArray, rotation: Int, frame: Int) {
        val chunk = 1100
        val count = (jpeg.size + chunk - 1) / chunk
        if (count == 0 || count > 400) return
        for (i in 0 until count) {
            val from = i * chunk; val to = minOf(jpeg.size, from + chunk)
            val bb = ByteBuffer.allocate(9 + (to - from)).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(frame).putShort(i.toShort()).putShort(count.toShort()).put((rotation and 3).toByte()).put(jpeg, from, to - from)
            send(0x30, bb.array())
            if (i % 8 == 7) Thread.sleep(1)   // 一気に送ると、途中で欠けるので、少し間を空ける
        }
    }

    /** Ctrl+Alt+Del(ロック画面・UAC 対応の接続のときだけ、ホストが実行する)。 */
    fun sas() = send(0x26, ByteArray(0), twice = true)
    fun scroll(amount: Int) = send(0x23, le(amount.coerceIn(-32768, 32767), sizes = intArrayOf(2)))
    fun key(vk: Int, down: Boolean) = send(0x24, le(vk, if (down) 1 else 0, sizes = intArrayOf(2, 1)), twice = true)
    /** 持っていないカーソルの形の再送を頼む(同じ形は0.4秒に1回まで)。 */
    private fun requestShape(id: Int) {
        val now = System.currentTimeMillis()
        if (now - (shapeAsked[id] ?: 0L) < 400) return
        shapeAsked[id] = now
        send(0x14, le(id, sizes = intArrayOf(4)), twice = true)
    }
    fun text(s: String) = send(0x25, s.toByteArray(Charsets.UTF_8), twice = true)

    private fun run() {
        val sock = try {
            DatagramSocket().also { it.connect(InetSocketAddress(address, port)); it.soTimeout = 200; socket = it }
        } catch (e: Exception) { fail("接続できません: ${e.message}"); return }

        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var configured = false
        var lastHello = 0L; var lastAlive = 0L; var lastIdrReq = 0L
        val begin = System.currentTimeMillis()

        // 組み立て中のフレーム
        var cur = -1L; var parts = arrayOfNulls<ByteArray>(0); var got = 0; var curKey = false
        var haveKey = false
        var lastRecv = System.currentTimeMillis()

        fun requestIdr() {
            val now = System.currentTimeMillis()
            if (now - lastIdrReq > 300) { lastIdrReq = now; send(0x12, ByteArray(0)) }
        }

        while (running) {
            val now = System.currentTimeMillis()
            if (!configured) {
                if (now - lastHello > 500) {
                    lastHello = now
                    val hello = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).put(display.toByte()).putInt(kbps).putLong(System.currentTimeMillis()).put(((if (audio) 1 else 0) or (if (audio && AudioPlayer.opusSupported) 2 else 0)).toByte()).array()
                    send(0x10, hello)
                }
                if (now - begin > 6000) { fail("ホストから応答がありません(Deskport ホストが動いているか、鍵(QRの登録)とファイアウォールを確認)"); return }
            } else if (now - lastAlive > 1000) { lastAlive = now; send(0x11, ByteArray(0)) }
            if (configured && now - lastRecv > 5000) { fail("映像が途絶えました"); return }

            try { pkt.length = buf.size; sock.receive(pkt) } catch (_: java.net.SocketTimeoutException) { continue } catch (e: Exception) { if (running) fail("受信エラー: ${e.message}"); return }
            // 復号する(鍵が違う、改ざん、方向が違うものは捨てる)。以降は、復号した中身を buf に置き直して読む
            if (pkt.length < 12 + 16 + 1 || buf[0].toInt() != 1) continue
            val plain = try {
                recvCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, buf, 0, 12))
                recvCipher.doFinal(buf, 12, pkt.length - 12)
            } catch (_: Exception) { continue }
            System.arraycopy(plain, 0, buf, 0, plain.size)
            lastRecv = System.currentTimeMillis()
            val n = plain.size
            if (n < 1) continue
            when (buf[0].toInt()) {
                0x16 -> if (!configured && n >= 9) {
                    val host = ByteBuffer.wrap(buf, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    val sec = Math.abs(System.currentTimeMillis() - host) / 1000
                    fail("PC とスマホの時計が $sec 秒ずれています(60 秒以内に合わせてください)"); return
                }
                0x01 -> if (!configured && n >= 7) {
                    val bb = ByteBuffer.wrap(buf, 1, 6).order(ByteOrder.LITTLE_ENDIAN)
                    val w = bb.short.toInt() and 0xFFFF; val h = bb.short.toInt() and 0xFFFF
                    val fps = buf[5].toInt() and 0xFF
                    if (decoder.setup(VIDEO_FORMAT_H265, w, h, fps) != 0) { fail("映像のデコーダを用意できません"); return }
                    decoder.start()
                    configured = true; lastAlive = now
                    Log.i(TAG, "info ${w}x$h@$fps")
                    Stream.setSize(display, w, h)
                    onStarted()
                }
                0x03 -> if (n >= 6) {
                    val bb = ByteBuffer.wrap(buf, 1, n - 1).order(ByteOrder.LITTLE_ENDIAN)
                    val x = bb.short.toInt(); val y = bb.short.toInt(); val vis = bb.get().toInt() != 0
                    val shape = if (n >= 10) bb.int else 0
                    onCursor(x, y, vis, shape)
                    if (shape != 0 && !CursorShapes.has(shape)) requestShape(shape)
                }
                0x07 -> if (n > 10 && StreamSettings.audio) {
                    val bb = ByteBuffer.wrap(buf, 1, 9).order(ByteOrder.LITTLE_ENDIAN)
                    val aseq = bb.int; val rate = bb.int
                    if (rate in 8000..192000 && buf[9].toInt() == 2) AudioPlayer.feed(audioId, aseq, rate, buf.copyOfRange(10, n - (n - 10) % 4))
                }
                0x08 -> if (n > 5 && StreamSettings.audio) {
                    val aseq = ByteBuffer.wrap(buf, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    AudioPlayer.feedOpus(audioId, aseq, buf.copyOfRange(5, n))
                }
                0x04 -> if (n > 18) {
                    val bb = ByteBuffer.wrap(buf, 1, 17).order(ByteOrder.LITTLE_ENDIAN)
                    val id = bb.int; val w = bb.short.toInt() and 0xFFFF; val h = bb.short.toInt() and 0xFFFF
                    val hx = bb.short.toInt() and 0xFFFF; val hy = bb.short.toInt() and 0xFFFF
                    val busy = (bb.get().toInt() and 1) != 0
                    val idx = bb.short.toInt() and 0xFFFF; val cnt = bb.short.toInt() and 0xFFFF
                    if (cnt in 1..64 && idx < cnt && !CursorShapes.has(id)) {
                        val a = shapeParts.getOrPut(id) { arrayOfNulls<ByteArray>(cnt) }
                        if (a.size == cnt) {
                            a[idx] = buf.copyOfRange(18, n)
                            if (a.all { it != null }) {
                                shapeParts.remove(id)
                                val data = ByteArray(a.sumOf { it!!.size }); var off = 0
                                for (p in a) { System.arraycopy(p!!, 0, data, off, p.size); off += p.size }
                                CursorShapes.put(id, w, h, hx, hy, busy, data)
                            }
                        }
                    }
                }
                0x02 -> if (configured && n > 10) {
                    val bb = ByteBuffer.wrap(buf, 1, 9).order(ByteOrder.LITTLE_ENDIAN)
                    val key = (bb.get().toInt() and 1) != 0
                    val id = bb.int.toLong() and 0xFFFFFFFFL
                    val idx = bb.short.toInt() and 0xFFFF
                    val count = bb.short.toInt() and 0xFFFF
                    if (count == 0 || idx >= count) continue
                    if (id != cur) {
                        // 新しいフレームが始まった。前のが未完成なら欠けたということ
                        val diff = ((id - cur) and 0xFFFFFFFFL).toInt()   // 符号付きの差(巻き戻り対応)
                        if (cur >= 0 && diff < 0) continue                // 古いパケットは捨てる
                        if (cur >= 0 && got < parts.size) { haveKey = false; requestIdr() }
                        cur = id; parts = arrayOfNulls(count); got = 0; curKey = key
                    }
                    if (got > parts.size) continue                        // 完成済みのフレームの余りパケット
                    if (parts[idx] == null) { parts[idx] = buf.copyOfRange(10, n); got++ }
                    if (got == parts.size) {
                        val total = parts.sumOf { it!!.size }
                        val data = ByteArray(total); var off = 0
                        for (p in parts) { System.arraycopy(p!!, 0, data, off, p.size); off += p.size }
                        if (curKey) haveKey = true
                        if (haveKey) {
                            if (decoder.submit(data, total, id.toInt(), if (curKey) 1 else 0) != DR_OK) requestIdr()
                        } else requestIdr()
                        got = parts.size + 1   // 完成済みの印(次のフレームで欠け扱いにしない)
                    }
                }
            }
        }
    }

    private fun fail(msg: String) { Log.w(TAG, msg); running = false; onFailed(msg) }

    companion object {
        private const val TAG = "NativeReceiver"
        private val nextId = java.util.concurrent.atomic.AtomicLong(0)
    }
}
