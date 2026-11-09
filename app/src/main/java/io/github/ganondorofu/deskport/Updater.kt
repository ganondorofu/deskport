package io.github.ganondorofu.deskport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * アプリの自動更新。GitHub Releases の最新版から、署名つきの更新情報(manifest.json と manifest.sig)を取る。
 *  1. 署名を、アプリに埋め込んだ公開鍵で確かめる(合わなければ何もしない。配布元が乗っ取られても、偽のアプリは入らない)
 *  2. 版が新しければ、apk を取り、sha256 を更新情報と照らす
 *  3. システムのインストーラに渡す(確認の画面が出る。初回は「この提供元のアプリを許可」が要る)
 * 注意: 更新できるのは、いま入っているアプリと同じ署名鍵で作った apk だけ(Android の決まり)。
 */
object Updater {
    private const val BASE = "https://github.com/ganondorofu/deskport/releases/latest/download"
    /** host/src/update.rs の PUBLIC_KEY_HEX と同じ(同じ鍵で、PC用と端末用の両方を署名する)。 */
    private const val PUBLIC_KEY = "c8ce85c9096b59633bb1ef4098ba15735de2576fd27f67096c98e478d1c964e0"
    private const val ACTION = "io.github.ganondorofu.deskport.INSTALL_RESULT"

    class Available(val versionName: String, val versionCode: Long, val file: String, val sha256: String)

    var available by mutableStateOf<Available?>(null)
    var status by mutableStateOf("")

    /** エラー文から URL を除いて、短くする(画面からはみ出さないように)。 */
    private fun brief(m: String?): String {
        val t = (m ?: "原因不明").replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim()
        return if (t.length > 60) t.take(60) + "…" else t.ifEmpty { "原因不明" }
    }
    var busy by mutableStateOf(false)
    private var receiverSet = false

    private fun get(url: String, limit: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024); var total = 0
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n; if (total > limit) error("大きすぎます")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun currentCode(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** 更新を確認する。結果は available と status に出る。 */
    suspend fun check(ctx: Context) {
        if (busy) return
        busy = true; status = "確認中…"
        try {
            val a = withContext(Dispatchers.IO) {
                val manifest = get("$BASE/manifest.json", 64 * 1024)
                val sig = get("$BASE/manifest.sig", 256)
                val v = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(unhex(PUBLIC_KEY), 0)); update(manifest, 0, manifest.size) }
                if (!v.verifySignature(sig)) error("更新情報の署名が合いません(配布元が改ざんされた可能性があります)")
                val app = JSONObject(String(manifest)).getJSONObject("app")
                Available(app.getString("versionName"), app.getLong("versionCode"), app.getString("file"), app.getString("sha256").lowercase())
            }
            if (a.versionCode > currentCode(ctx)) { available = a; status = "新しい版 ${a.versionName} があります" }
            else { available = null; status = "最新の版です(${currentName(ctx)})" }
        } catch (e: Exception) {
            status = "確認できませんでした: ${brief(e.message)}"
        } finally { busy = false }
    }

    /** 新しい apk を取って、インストーラに渡す。 */
    suspend fun install(ctx: Context) {
        val a = available ?: return
        if (busy) return
        busy = true; status = "ダウンロード中…"
        try {
            if (a.file.contains('/') || a.file.contains('\\')) error("ファイル名が不正です")
            val data = withContext(Dispatchers.IO) { get("$BASE/${a.file}", 200 * 1024 * 1024) }
            if (hex(MessageDigest.getInstance("SHA-256").digest(data)) != a.sha256) error("ダウンロードしたファイルが、更新情報と合いません")
            ensureReceiver(ctx.applicationContext)
            val pi = ctx.packageManager.packageInstaller
            val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            pi.openSession(id).use { s ->
                s.openWrite("app", 0, data.size.toLong()).use { it.write(data); s.fsync(it) }
                val pending = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(pending.intentSender)
            }
            status = "インストールの確認を待っています…"
        } catch (e: Exception) {
            status = "更新できませんでした: ${brief(e.message)}"
        } finally { busy = false }
    }

    /** インストーラからの返事(確認の画面を出す、成功、失敗)を受ける。 */
    private fun ensureReceiver(app: Context) {
        if (receiverSet) return
        receiverSet = true
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> status = "更新しました"
                    else -> status = "更新できませんでした: ${brief(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))}"
                }
            }
        }, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
    }
}
