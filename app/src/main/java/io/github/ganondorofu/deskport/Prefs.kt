package io.github.ganondorofu.deskport

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ホストの接続先。address は IP または名前、token は QR で受け取った鍵。
 */
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val token: String,
    /** 配信できるディスプレイの数。 */
    val displayCount: Int = 1,
    /** ホスト(deskport-host)の UDP ポート。 */
    val nativePort: Int = 49000,
    /** 同じPCの別のアドレス(LAN と VPN など。種類は問わない)。つなぐときに、address と合わせて全部に問い合わせ、最も速く応えたものを使う。 */
    val alt: List<String> = emptyList(),
) {
    /** つなぎ先の候補(address が先)。 */
    fun candidates(): List<String> = (listOf(address) + alt).filter { it.isNotBlank() }.distinct()
}

/** ホスト一覧(メモリ上の状態。保存は Prefs が担当)。 */
object HostStore {
    val hosts = mutableStateListOf<Host>()
    var lastId: String? = null

    fun last(): Host? = hosts.firstOrNull { it.id == lastId } ?: hosts.firstOrNull()

    fun upsert(h: Host) {
        val i = hosts.indexOfFirst { it.id == h.id }
        if (i >= 0) hosts[i] = h else hosts.add(h)
    }
}

/**
 * 設定の保存と読み込み(アプリ専用のストレージ)。
 * 注意: 鍵(token)は暗号化せず保存している。アプリ専用領域なので他アプリからは読めないが、
 * 端末をroot化した場合などは読める。将来は Android Keystore で暗号化する。
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("deskport", Context.MODE_PRIVATE)
        load(ctx)
        AppLock.load()
    }

    /** 補助: 文字列をそのまま保存/取得する(証明書など)。null を渡すと削除。 */
    fun getString(key: String): String? = sp.getString(key, null)
    fun putString(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }

    private fun load(ctx: Context) {
        KbSettings.layout = KbLayout.entries.getOrElse(sp.getInt("kb.layout", 0)) { KbLayout.US }
        KbSettings.holdOnDoubleTap = sp.getBoolean("kb.hold", true)
        KbSettings.size = KbSize.entries.getOrElse(sp.getInt("kb.size", 0)) { KbSize.COMPACT }
        KbSettings.inputMethod = InputMethod.entries.getOrElse(sp.getInt("kb.method", 0)) { InputMethod.DIRECT }
        KbSettings.keyHeight = sp.getInt("kb.height", 1).coerceIn(0, 2)
        KbSettings.showNumRow = sp.getBoolean("kb.num2", true)
        KbSettings.showFnRow = sp.getBoolean("kb.fn", false)
        KbSettings.showShortcuts = sp.getBoolean("kb.sc", true)
        KbSettings.padFirst = sp.getBoolean("kb.padFirst", false)
        KbSettings.areaH = sp.getFloat("area.h", 0.42f).coerceIn(0.2f, 0.7f)
        KbSettings.areaVBar = sp.getFloat("area.vbar", 0.50f).coerceIn(0.2f, 0.75f)
        KbSettings.areaVWide = sp.getFloat("area.vwide", 0.41f).coerceIn(0.2f, 0.75f)
        KbSettings.splitH = sp.getFloat("kb.splitH", 0.64f).coerceIn(0.25f, 0.8f)
        KbSettings.splitV = sp.getFloat("kb.splitV", 0.62f).coerceIn(0.25f, 0.8f)
        sp.getString("kb.shortcuts", null)?.let { s ->
            KbSettings.shortcuts.clear()
            JSONArray(s).let { a -> for (i in 0 until a.length()) KbSettings.shortcuts.add(a.getString(i)) }
        }
        PointerSettings.speed = sp.getFloat("ptr.speed", 1.5f)
        PointerSettings.accel = sp.getBoolean("ptr.accel", true)
        PointerSettings.scrollSpeed = sp.getFloat("ptr.scroll", 1f)
        PointerSettings.naturalPad = sp.getBoolean("ptr.natPad", false)
        PointerSettings.naturalScreen = sp.getBoolean("ptr.natScreen", true)
        PointerSettings.naturalWheel = sp.getBoolean("ptr.natWheel", true)
        GestureSettings.enabled = sp.getBoolean("g.enabled", true)
        GTrigger.entries.forEach { t -> GestureSettings.map[t] = GAction.entries.firstOrNull { it.name == sp.getString("g2." + t.name, null) } ?: GestureSettings.defaults()[t]!! }
        PointerSettings.cursorScale = sp.getFloat("ptr.cursor", 0.6f)
        PointerSettings.inertia = sp.getBoolean("ptr.inertia", false)
        PointerSettings.showButtons = sp.getBoolean("ptr.buttons", true)
        PointerSettings.showWheel = sp.getBoolean("ptr.wheel", true)
        StreamSettings.mbps = sp.getFloat("stream.mbps", 20f)
        StreamSettings.audio = sp.getBoolean("audio.on", true)
        I18n.mode = sp.getString("lang", "auto") ?: "auto"
        StreamSettings.volume = sp.getFloat("audio.vol", 1f)
        AudioPlayer.volume = StreamSettings.volume
        ViewerSettings.touchMode = TouchMode.entries.getOrElse(sp.getInt("view.mode", 0)) { TouchMode.VIEW }
        ViewerSettings.locked = sp.getBoolean("view.locked", false)
        ViewerSettings.follow = sp.getBoolean("view.follow", false)
        LayoutState.kbVisible = sp.getBoolean("ui.kb", true)
        LayoutState.padVisible = sp.getBoolean("ui.pad", true)
        LayoutState.hybridVisible = sp.getBoolean("ui.hybrid", false)
        LayoutState.selected = sp.getInt("ui.sel", -1)
        // モード別の設定(上の値を読み終えてから。初期値は、いまの値を土台にして作る)
        DeviceSettings.foldMode = sp.getBoolean("fold.mode", ctx.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle"))
        Profiles.perMode = DeviceSettings.foldMode && sp.getBoolean("prof.perMode", true)
        sp.getString("prof.store", null)?.let { s ->
            val o = JSONObject(s)
            Posture.entries.forEach { p -> o.optJSONObject(p.name)?.let { Profiles.store[p] = ModeProfile.fromJson(it, Profiles.defaultFor(p)) } }
        }
        SessionPrefs.clipboardSync = sp.getBoolean("clip.sync", false)
        FileSettings.compress = sp.getBoolean("file.compress", false)
        PointerSettings.showOffscreenIndicator = sp.getBoolean("ptr.offscreen", true)
        HostStore.lastId = sp.getString("host.last", null)
        sp.getString("hosts", null)?.let { s ->
            HostStore.hosts.clear()
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optInt("nativePort", 0) == 0) continue   // 旧版の Sunshine 用の登録は引き継がない
                HostStore.hosts.add(Host(o.getString("id"), o.getString("name"), o.getString("address"), TokenVault.open(o.getString("token")), o.optInt("displayCount", 1), o.optInt("nativePort", 49000), o.optJSONArray("alt")?.let { j -> (0 until j.length()).map { j.getString(it) } } ?: emptyList()))
            }
        }
    }

    fun save() {
        Profiles.syncActive()
        val prof = JSONObject().also { o -> Profiles.store.forEach { (p, m) -> o.put(p.name, m.toJson()) } }
        val hosts = JSONArray().also { a ->
            HostStore.hosts.forEach { h ->
                a.put(JSONObject().put("id", h.id).put("name", h.name).put("address", h.address).put("token", TokenVault.seal(h.token)).put("displayCount", h.displayCount).put("nativePort", h.nativePort).put("alt", JSONArray(h.alt)))
            }
        }
        sp.edit()
            .putInt("kb.layout", KbSettings.layout.ordinal)
            .putInt("kb.size", KbSettings.size.ordinal)
            .putBoolean("kb.hold", KbSettings.holdOnDoubleTap)
            .putInt("kb.method", KbSettings.inputMethod.ordinal)
            .putInt("kb.height", KbSettings.keyHeight)
            .putBoolean("kb.num2", KbSettings.showNumRow)
            .putBoolean("kb.fn", KbSettings.showFnRow)
            .putBoolean("kb.sc", KbSettings.showShortcuts)
            .putBoolean("kb.padFirst", KbSettings.padFirst)
            .putFloat("area.h", KbSettings.areaH)
            .putFloat("area.vbar", KbSettings.areaVBar)
            .putFloat("area.vwide", KbSettings.areaVWide)
            .putFloat("kb.splitH", KbSettings.splitH)
            .putFloat("kb.splitV", KbSettings.splitV)
            .putString("kb.shortcuts", JSONArray(KbSettings.shortcuts.toList()).toString())
            .putFloat("ptr.speed", PointerSettings.speed)
            .putBoolean("ptr.accel", PointerSettings.accel)
            .putFloat("ptr.scroll", PointerSettings.scrollSpeed)
            .putBoolean("ptr.natPad", PointerSettings.naturalPad)
            .putBoolean("ptr.natScreen", PointerSettings.naturalScreen)
            .putBoolean("ptr.natWheel", PointerSettings.naturalWheel)
            .putBoolean("g.enabled", GestureSettings.enabled)
            .putFloat("ptr.cursor", PointerSettings.cursorScale)
            .putInt("view.mode", ViewerSettings.touchMode.ordinal)
            .putBoolean("view.locked", ViewerSettings.locked)
            .putBoolean("view.follow", ViewerSettings.follow)
            .putBoolean("ptr.inertia", PointerSettings.inertia)
            .putFloat("stream.mbps", StreamSettings.mbps)
            .putBoolean("audio.on", StreamSettings.audio)
            .putString("lang", I18n.mode)
            .putFloat("audio.vol", StreamSettings.volume)
            .putBoolean("ptr.buttons", PointerSettings.showButtons)
            .putBoolean("ptr.wheel", PointerSettings.showWheel)
            .putBoolean("fold.mode", DeviceSettings.foldMode)
            .putBoolean("ui.kb", LayoutState.kbVisible)
            .putBoolean("ui.pad", LayoutState.padVisible)
            .putBoolean("ui.hybrid", LayoutState.hybridVisible)
            .putInt("ui.sel", LayoutState.selected)
            .putBoolean("prof.perMode", Profiles.perMode)
            .putString("prof.store", prof.toString())
            .putBoolean("clip.sync", SessionPrefs.clipboardSync)
            .putBoolean("file.compress", FileSettings.compress)
            .putBoolean("ptr.offscreen", PointerSettings.showOffscreenIndicator)
            .putString("host.last", HostStore.lastId)
            .putString("hosts", hosts.toString())
            .also { ed -> GTrigger.entries.forEach { t -> ed.putString("g2." + t.name, (GestureSettings.map[t] ?: GAction.NONE).name) } }
            .apply()
    }

    /** 保存対象の値をひとまとめにしたもの。snapshotFlow で変化を検知するために使う。 */
    fun snapshotKey(): List<Any?> = listOf(
        KbSettings.layout, KbSettings.size, KbSettings.holdOnDoubleTap, KbSettings.inputMethod, KbSettings.keyHeight, KbSettings.showNumRow,
        KbSettings.showFnRow, KbSettings.showShortcuts, KbSettings.shortcuts.toList(),
        KbSettings.padFirst, KbSettings.splitH, KbSettings.splitV, KbSettings.areaH, KbSettings.areaVBar, KbSettings.areaVWide,
        PointerSettings.speed, PointerSettings.accel, PointerSettings.scrollSpeed, PointerSettings.naturalPad, PointerSettings.naturalScreen, PointerSettings.naturalWheel, GestureSettings.enabled, GestureSettings.map.toMap(), PointerSettings.cursorScale,
        ViewerSettings.touchMode, ViewerSettings.locked, ViewerSettings.follow, PointerSettings.inertia, StreamSettings.mbps, StreamSettings.audio, I18n.mode, StreamSettings.volume, PointerSettings.showButtons, PointerSettings.showWheel, DeviceSettings.foldMode,
        LayoutState.kbVisible, LayoutState.padVisible, LayoutState.hybridVisible, LayoutState.selected, Profiles.perMode, Profiles.activePosture,
        SessionPrefs.clipboardSync, FileSettings.compress, PointerSettings.showOffscreenIndicator, HostStore.lastId, HostStore.hosts.toList(),
    )
}

/**
 * ホストの鍵を、端末の Android Keystore の鍵で暗号化して保存する(設定ファイルを取り出されても、鍵は読めない)。
 * 暗号化に失敗したときは、そのまま保存する(以前の形式も読める)。
 */
object TokenVault {
    private const val ALIAS = "deskport-token"
    private const val PREFIX = "enc1:"

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return g.generateKey()
    }

    fun seal(plain: String): String = try {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray())
        PREFIX + android.util.Base64.encodeToString(c.iv + ct, android.util.Base64.NO_WRAP)
    } catch (e: Exception) { plain }

    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored   // 以前の形式(平文)。次の保存で暗号化される
        return try {
            val raw = android.util.Base64.decode(stored.removePrefix(PREFIX), android.util.Base64.NO_WRAP)
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12)) }
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) { "" }   // 鍵が失われた(端末の初期化など): 登録し直しが必要
    }
}
