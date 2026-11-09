package io.github.ganondorofu.deskport

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/** 自作ホスト(deskport-host)が生きていて、鍵が合っているかを調べる(暗号化した問い合わせを送り、返事を待つ)。 */
object NativeProbe {
    suspend fun ping(h: Host): Boolean = rtt(h) >= 0

    /** 全部の候補のアドレスに同時に問い合わせて、最も速く応えたものを選ぶ。1つも応えなければ null。 */
    suspend fun pick(h: Host): Host? {
        // 別のスコープで問い合わせる(応えない候補の完了を待たずに、返せるようにするため)
        val scope = CoroutineScope(Dispatchers.IO)
        val ds = h.candidates().map { a -> a to scope.async { rtt(h.copy(address = a)) } }
        var firstOk = 0L
        while (true) {
            val done = ds.filter { it.second.isCompleted }
            val ok = done.mapNotNull { (a, d) -> d.getCompleted().takeIf { it >= 0 }?.let { a to it } }
            val now = System.nanoTime() / 1_000_000
            if (ok.isNotEmpty()) {
                if (firstOk == 0L) firstOk = now
                // 最初の応答から、少し(最大150ms)だけ待って、もっと速いものがあれば、そちらを選ぶ。応えない候補は、待たない
                if (done.size == ds.size || now - firstOk >= 150) {
                    val best = ok.minByOrNull { it.second }!!
                    android.util.Log.i("NativeProbe", "${h.name}: ${best.first}=${best.second}ms(選択)")
                    return h.copy(address = best.first)
                }
            } else if (done.size == ds.size) {
                android.util.Log.i("NativeProbe", "${h.name}: どの候補も応答なし")
                return null
            }
            delay(10)
        }
    }

    /** 往復にかかった時間(ミリ秒)。応えなければ -1。 */
    suspend fun rtt(h: Host): Long = withContext(Dispatchers.IO) {
        try {
            val t0 = System.nanoTime()
            val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(h.token.toByteArray()), "AES")
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it); it[0] = 0 }   // 方向0=端末→ホスト。残りは乱数(1回しか使わない)
            val body = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(0x15).putInt(0).array()
            val enc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
            val out = nonce + enc.doFinal(body)
            DatagramSocket().use { s ->
                s.soTimeout = 300
                s.connect(InetSocketAddress(h.address, h.nativePort))
                val dec = Cipher.getInstance("AES/GCM/NoPadding")
                val buf = ByteArray(256)
                var result = -1L
                // VPN は最初の1発目が遅れることがあるので、最大7回(約2秒)送り直す
                for (attempt in 0 until 7) {
                    s.send(DatagramPacket(out, out.size))
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                    if (p.length < 12 + 16 + 1 || buf[0].toInt() != 1) continue
                    val ok = try {
                        dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, buf, 0, 12))
                        dec.doFinal(buf, 12, p.length - 12).firstOrNull()?.toInt() == 0x05
                    } catch (_: Exception) { false }
                    if (ok) { result = (System.nanoTime() - t0) / 1_000_000; break }
                }
                result
            }
        } catch (_: Exception) { -1L }
    }
}

/**
 * QRコードの内容(deskport://pair?n=PC名&p=ポート&d=ディスプレイ数&k=鍵&a=アドレス&a=...)から、ホストを登録する。
 * 1台のPCを1件で登録し、アドレスは全部持つ(LAN、VPN など、種類は問わない)。同じPCを読み直したら、鍵などを更新する。
 */
object PairQr {
    /** 登録したアドレスの数を返す。形式が違えば 0。 */
    fun import(text: String): Int {
        val u = try { Uri.parse(text.trim()) } catch (_: Exception) { return 0 }
        if (u.scheme != "deskport" || u.host != "pair") return 0
        val name = u.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: "PC"
        val port = u.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return 0
        val disp = u.getQueryParameter("d")?.toIntOrNull()?.coerceIn(1, 4) ?: 1
        val key = u.getQueryParameter("k")?.takeIf { it.length >= 16 } ?: return 0
        val addrs = u.getQueryParameters("a").filter { it.isNotBlank() }
        if (addrs.isEmpty()) return 0
        // 1台のPCは1件。古い形式(アドレスごとに1件)で登録したものは、置き換える
        HostStore.hosts.removeAll { it.id.startsWith("native:$name:") }
        HostStore.upsert(Host(id = "native:$name", name = name, address = addrs[0], token = key, displayCount = disp, nativePort = port, alt = addrs.drop(1)))
        return addrs.size
    }
}
