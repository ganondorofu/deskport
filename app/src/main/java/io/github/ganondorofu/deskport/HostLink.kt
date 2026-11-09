package io.github.ganondorofu.deskport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class RemoteFile(val name: String, val size: Long, val mtime: Long)

/** PC の置き場所の一覧。dir は、PC 上の絶対パス(スマホの画面に、保存先として見せる)。 */
data class RemoteList(val dir: String, val files: List<RemoteFile>)

/** 転送の結果。wire は、実際に通信した量(圧縮したときは、元より小さい)。 */
data class TransferResult(val name: String, val wire: Long)

/**
 * ホストとのファイル転送とクリップボード(TCP、暗号化つき)。形式は host/src/files.rs の冒頭を参照。
 *  - 接続のたびに乱数を交換して、その接続だけの鍵を作る(別の接続の記録を再送しても通らない)。
 *  - ホストが最初に送るフレームを復号できて初めて、相手を本物のホストとして扱う。
 */
class HostConn private constructor(private val sock: Socket, key: ByteArray) : Closeable {
    private val aesKey = SecretKeySpec(key, "AES")
    private val input = DataInputStream(sock.getInputStream().buffered())
    private val output = sock.getOutputStream()
    private var sendN = 0L
    private var recvN = 0L
    var hostName = ""
        private set

    private fun nonce(dir: Int, n: Long) =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(dir.toByte()).put(0).put(0).put(0).putLong(n).array()

    private fun send(data: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce(0, sendN++)))
        val ct = c.doFinal(data)
        output.write(ByteBuffer.allocate(4 + ct.size).order(ByteOrder.LITTLE_ENDIAN).putInt(ct.size).put(ct).array())
        output.flush()
    }

    private fun recv(): ByteArray {
        val h = ByteArray(4).also { input.readFully(it) }
        val n = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).int
        if (n < 16 || n > MAX_FRAME + 16) throw IOException("不正なフレーム")
        val ct = ByteArray(n).also { input.readFully(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, nonce(1, recvN++)))
        return try { c.doFinal(ct) } catch (e: Exception) { throw IOException("復号できません(鍵が違う可能性があります)") }
    }

    /** 失敗の返事([1][理由])なら例外にして、成功なら先頭の [0] を除いた中身を返す。 */
    private fun reply(): ByteArray {
        val r = recv()
        if (r.isEmpty()) throw IOException("空の返事")
        if (r[0].toInt() != 0) throw IOException(String(r, 1, r.size - 1, Charsets.UTF_8))
        return r.copyOfRange(1, r.size)
    }

    private fun call(op: Int, body: ByteArray = ByteArray(0)): ByteArray {
        send(byteArrayOf(op.toByte()) + body)
        return reply()
    }

    fun clipGet(): String = String(call(0x01), Charsets.UTF_8)
    fun clipSet(text: String) { call(0x02, text.toByteArray(Charsets.UTF_8)) }

    fun list(): RemoteList {
        val j = JSONObject(String(call(0x03), Charsets.UTF_8))
        val a = j.getJSONArray("files")
        return RemoteList(
            j.optString("dir", ""),
            List(a.length()) { i -> a.getJSONObject(i).let { RemoteFile(it.getString("name"), it.getLong("size"), it.getLong("mtime")) } },
        )
    }

    /** PC のエクスプローラーで、そのファイルを表示する。 */
    fun reveal(name: String) { call(0x08, name.toByteArray(Charsets.UTF_8)) }

    /** PC の置き場所から、そのファイルを消す。 */
    fun delete(name: String) { call(0x09, name.toByteArray(Charsets.UTF_8)) }

    /**
     * ファイルを置く。size が分かればそのまま流す。不明(-1)なら全部読み込んでから送る。
     * compress=true なら、deflate で圧縮して送る(PC が展開する)。保存された名前と、実際に通信した量を返す。
     * onProgress は、元のファイルの何バイト送ったか。ここで例外を投げると(キャンセルなど)、転送をやめる。
     */
    fun put(name: String, src: InputStream, size: Long, compress: Boolean, onProgress: (Long) -> Unit): TransferResult {
        val data = if (size < 0) src.readBytes() else null
        val len = data?.size?.toLong() ?: size
        val nb = name.toByteArray(Charsets.UTF_8)
        call(if (compress) 0x06 else 0x04, ByteBuffer.allocate(2 + nb.size + 8).order(ByteOrder.LITTLE_ENDIAN).putShort(nb.size.toShort()).put(nb).putLong(len).array())
        var sent = 0L
        var wire = 0L
        val buf = ByteArray(CHUNK)
        val def = if (compress) Deflater(3, true) else null
        val zout = ByteArray(CHUNK + CHUNK / 8 + 1024)
        try {
            while (sent < len) {
                val want = minOf(CHUNK.toLong(), len - sent).toInt()
                val n = if (data != null) { System.arraycopy(data, sent.toInt(), buf, 0, want); want } else src.read(buf, 0, want)
                if (n < 0) throw IOException("元のファイルが途中で終わりました")
                if (def != null) {
                    // 区切りごとに SYNC_FLUSH で出し切って、PC が、届いた分から展開できるようにする
                    def.setInput(buf, 0, n)
                    while (true) {
                        val z = def.deflate(zout, 0, zout.size, Deflater.SYNC_FLUSH)
                        if (z > 0) { send(zout.copyOf(z)); wire += z }
                        if (z < zout.size) break
                    }
                } else { send(buf.copyOf(n)); wire += n }
                sent += n
                onProgress(sent)
            }
            if (def != null) send(ByteArray(0))   // 終わりの印
        } finally { def?.end() }
        return TransferResult(String(reply(), Charsets.UTF_8), wire)
    }

    /** ファイルを取る。compress=true なら、PC が圧縮して送り、ここで展開する。実際に通信した量を返す。 */
    fun get(name: String, out: OutputStream, compress: Boolean, onProgress: (Long) -> Unit): Long {
        val size = ByteBuffer.wrap(call(if (compress) 0x07 else 0x05, name.toByteArray(Charsets.UTF_8))).order(ByteOrder.LITTLE_ENDIAN).long
        var got = 0L
        var wire = 0L
        if (!compress) {
            while (got < size) {
                val c = recv()
                got += c.size; wire += c.size
                if (got > size) throw IOException("データが不正です")
                out.write(c)
                onProgress(got)
            }
            return wire
        }
        val inf = Inflater(true)
        val buf = ByteArray(CHUNK)
        try {
            while (true) {
                val f = recv()
                if (f.isEmpty()) break   // 終わりの印
                wire += f.size
                inf.setInput(f)
                while (true) {
                    val n = inf.inflate(buf)
                    if (n > 0) {
                        got += n
                        if (got > size) throw IOException("データが不正です")
                        out.write(buf, 0, n)
                        onProgress(got)
                    }
                    if (n == 0 && (inf.needsInput() || inf.finished())) break
                }
            }
        } catch (e: java.util.zip.DataFormatException) { throw IOException("データが不正です")
        } finally { inf.end() }
        if (got != size) throw IOException("データの大きさが合いません")
        return wire
    }

    override fun close() { try { sock.close() } catch (_: Exception) {} }

    companion object {
        private const val MAX_FRAME = 1 shl 20
        private const val CHUNK = 256 * 1024

        /** つなぐ。手順は files.rs の冒頭。失敗したら例外。 */
        fun open(h: Host): HostConn {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(h.address, h.nativePort), 3000)
                s.soTimeout = 30_000
                val cr = ByteArray(16).also { SecureRandom().nextBytes(it) }
                s.getOutputStream().apply { write(cr); flush() }
                val sr = ByteArray(16).also { DataInputStream(s.getInputStream()).readFully(it) }
                val md = MessageDigest.getInstance("SHA-256")
                md.update("deskport-tcp-v1".toByteArray())
                md.update(MessageDigest.getInstance("SHA-256").digest(h.token.toByteArray()))
                md.update(cr); md.update(sr)
                val conn = HostConn(s, md.digest())
                val hello = conn.recv()
                if (hello.isEmpty() || hello[0] != 0x80.toByte()) throw IOException("ホストの応答が不正です")
                conn.hostName = String(hello, 1, hello.size - 1, Charsets.UTF_8)
                return conn
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}

/** 呼ぶたびに接続して、終わったら閉じる(単発の操作用)。ファイル転送は、通常のポート(TCP 49000)を使う。 */
class HostLink(private val h: Host) {
    private suspend fun <T> once(block: (HostConn) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            HostConn.open(h).use { Result.success(block(it)) }
        } catch (e: Exception) {
            android.util.Log.w("HostLink", "${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun list(): RemoteList? = once { it.list() }.getOrNull()
    suspend fun upload(name: String, input: InputStream, size: Long, compress: Boolean, onProgress: (Long) -> Unit): Result<TransferResult> =
        once { it.put(name, input, size, compress, onProgress) }
    suspend fun download(name: String, out: OutputStream, compress: Boolean, onProgress: (Long) -> Unit): Result<Long> =
        once { it.get(name, out, compress, onProgress) }
    suspend fun reveal(name: String): Result<Unit> = once { it.reveal(name) }
    suspend fun delete(name: String): Result<Unit> = once { it.delete(name) }
}
