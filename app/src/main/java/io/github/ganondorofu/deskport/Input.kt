package io.github.ganondorofu.deskport

import androidx.compose.ui.geometry.Offset

/**
 * 画面の操作(カーソル移動・クリック・スクロール・キー・文字)をホストへ送る出口。
 * 配信中(STREAMING)だけ実際に送る。カーソル表示(RemoteCursor)は常に動かす。
 * キャンバスは、ディスプレイ1枚あたり 1920x1080 の枠を横に並べたもの。実際の解像度への変換は Stream.toHost。
 */
object RemoteInput {
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 3
    const val BACK = 4
    const val FORWARD = 5

    private val live get() = Stream.state == Stream.State.STREAMING
    private val nat get() = Stream.nativeInput

    /** 最後に手元でカーソルを動かした時刻。この直後は、ホストが報告する位置で上書きしない(動きがガタつくため)。 */
    @Volatile var lastLocalMoveMs = 0L

    /** 自作ホストのとき、カーソルが動ける範囲(キャンバスの x)。単独表示なら、そのディスプレイの中だけ。 */
    private fun nativeRangeX(): ClosedFloatingPointRange<Float> {
        val n = Session.host?.displayCount ?: 1
        return 0f..(n * Stream.WIDTH - 1f)   // 1画面ずつの表示でも、他のディスプレイへ出られる
    }

    /**
     * 相対移動(トラックパッド、画面パッド)。小数部は次回に繰り越す。
     * 自作ホストでは、手元のカーソルを即座に動かし、ホストへは「絶対位置」を送る。
     * 相対移動を送ると、Windows のポインタ加速でホスト側の動きが手元とずれ、その差を直すたびにカーソルがガタつくため。
     */
    fun move(d: Offset) {
        if (live) {
            val r = nativeRangeX()
            RemoteCursor.set((RemoteCursor.x + d.x).coerceIn(r), (RemoteCursor.y + d.y).coerceIn(0f, Stream.HEIGHT - 1f))
            lastLocalMoveMs = System.currentTimeMillis()
            sendAbsolute()
            return
        }
        RemoteCursor.moveBy(d)
    }

    /** 手元のカーソルの位置を、そのディスプレイの配信を通して、ホストへ絶対位置として送る。 */
    private fun sendAbsolute() {
        val k = Stream.displayOf(RemoteCursor.x)
        val exact = Stream.nativeInputFor(k)?.takeIf { Stream.nativeDisplays.contains(k) }
        if (exact != null) {
            val (sx, sy) = Stream.toHost(k, RemoteCursor.x, RemoteCursor.y)
            exact.mouseAbs(sx, sy)
        } else {
            // そのディスプレイを受けていない(1画面ずつの表示で、別のディスプレイへ出た): 受けている画面を基準に、範囲の外の座標で送る
            val (kr, r) = Stream.inputTarget(k) ?: return
            val (sx, sy) = Stream.toHostFree(kr, RemoteCursor.x, RemoteCursor.y)
            r.mouseAbs(sx, sy)
        }
    }

    /** 絶対位置(タップモード、マウス)。キャンバス座標で受け取る。 */
    fun moveTo(x: Float, y: Float) {
        RemoteCursor.set(x, y)
        lastLocalMoveMs = System.currentTimeMillis()
        if (!live) return
        sendAbsolute()
    }

    fun buttonDown(button: Int) { if (live) nat?.button(button, true) }
    fun buttonUp(button: Int) { if (live) nat?.button(button, false) }

    fun click(button: Int = LEFT, count: Int = 1) {
        repeat(count) { buttonDown(button); buttonUp(button) }
    }

    /** スクロール。正の値で上方向。amount は画面上の移動量(px)で、1ノッチ=120 に換算する。 */
    fun scroll(amount: Float) {
        if (!live) return
        val v = (amount * 3f).toInt()
        if (v != 0) nat?.scroll(v)
    }

    /** 文字列をそのまま入力する(日本語を含む。まとめて/ライブ入力用)。 */
    fun text(s: String) { if (live && s.isNotEmpty()) nat?.text(s) }

    // ---- キー ----
    private val modKeys = mapOf("Shift" to 0xA0, "Ctrl" to 0xA2, "Alt" to 0xA4, "Win" to 0x5B)

    private val named = mapOf(
        "Enter" to 0x0D, "⌫" to 0x08, "Backspace" to 0x08, "Space" to 0x20, "Tab" to 0x09, "Esc" to 0x1B,
        "Del" to 0x2E, "Delete" to 0x2E, "Home" to 0x24, "End" to 0x23, "PgUp" to 0x21, "PgDn" to 0x22,
        "←" to 0x25, "↑" to 0x26, "→" to 0x27, "↓" to 0x28,
        "Caps" to 0x14, "Ins" to 0x2D, "ScrLk" to 0x91, "Pause" to 0x13, "NumLk" to 0x90,
        "Num0" to 0x60, "Num1" to 0x61, "Num2" to 0x62, "Num3" to 0x63, "Num4" to 0x64, "Num5" to 0x65, "Num6" to 0x66, "Num7" to 0x67, "Num8" to 0x68, "Num9" to 0x69,
        "Num*" to 0x6A, "Num+" to 0x6B, "Num-" to 0x6D, "Num." to 0x6E, "Num/" to 0x6F, "半/全" to 0x19, "無変換" to 0x1D, "変換" to 0x1C, "かな" to 0x15, "PrtSc" to 0x2C,
    )

    /** 記号キー(US配列の刻印)。JIS配列では、刻印の違うキーを別に割り当てる。 */
    private fun symbolVk(c: Char): Int? {
        val jis = KbSettings.layout == KbLayout.JIS
        return when (c) {
            '`' -> 0xC0; '-' -> 0xBD; '=' -> 0xBB; '[' -> 0xDB; ']' -> 0xDD; '\\' -> 0xDC
            ';' -> if (jis) 0xBB else 0xBA; '\'' -> 0xDE; ',' -> 0xBC; '.' -> 0xBE; '/' -> 0xBF
            '^' -> 0xDE; '¥' -> 0xDC; '@' -> 0xC0; ':' -> 0xBA; '_' -> 0xE2
            else -> null
        }
    }

    private fun vkOf(label: String): Int? {
        named[label]?.let { return it }
        if (label.length == 1) {
            val c = label[0].uppercaseChar()
            if (c in 'A'..'Z' || c in '0'..'9') return c.code
            symbolVk(label[0])?.let { return it }
        }
        if (label.length in 2..3 && label[0] == 'F') label.drop(1).toIntOrNull()?.takeIf { it in 1..12 }?.let { return 0x6F + it }
        return null
    }

    private val modNames = modKeys.keys

    /** 「Ctrl+Shift+Esc」のような組み合わせとして、送れるか。 */
    fun validCombo(spec: String): Boolean {
        val parts = spec.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 4) return false
        val last = parts.last()
        return parts.dropLast(1).all { normalizeKey(it) in modNames } && (vkOf(normalizeKey(last)) != null || normalizeKey(last) in modNames)
    }

    private fun normalizeKey(k: String): String {
        val t = k.trim()
        return when (t.lowercase()) {
            "ctrl", "control" -> "Ctrl"; "shift" -> "Shift"; "alt" -> "Alt"; "win", "windows", "super" -> "Win"
            "enter", "return" -> "Enter"; "esc", "escape" -> "Esc"; "tab" -> "Tab"; "space" -> "Space"
            "del", "delete" -> "Del"; "home" -> "Home"; "end" -> "End"; "pgup", "pageup" -> "PgUp"; "pgdn", "pagedown" -> "PgDn"
            "backspace", "bs" -> "Backspace"
            else -> if (t.length == 1) t.uppercase() else if (t.length in 2..3 && t[0] in "fF") "F" + t.drop(1) else t
        }
    }

    fun normalizeCombo(spec: String): String =
        spec.split("+").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("+") { normalizeKey(it) }

    private fun key(vk: Int, down: Boolean) {
        nat?.key(vk, down)
    }

    /** "Ctrl+Shift+Esc" のような組み合わせ、または単独のキーを押して離す。修飾キーは先に押し、最後に離す。 */
    /** 押したままにしている修飾キー(名前)。 */
    val heldMods = androidx.compose.runtime.mutableStateListOf<String>()

    /** 押したままにした修飾キーを、すべて離す。 */
    fun releaseHeld() {
        heldMods.toList().asReversed().forEach { n -> modKeys[n]?.let { key(it, false) } }
        heldMods.clear()
    }

    /** 修飾キー(Ctrl、Alt など)を押したままにする(固定)。 */
    fun holdMod(name: String) {
        if (!live || name in heldMods) return
        val vk = modKeys[name] ?: return
        key(vk, true); heldMods.add(name)
    }

    /** 固定した修飾キーを1つだけ離す。 */
    fun releaseMod(name: String) {
        if (name !in heldMods) return
        modKeys[name]?.let { key(it, false) }
        heldMods.remove(name)
    }

    fun combo(spec: String) {
        if (!live) return
        val parts = spec.split("+").map { normalizeKey(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val mods = parts.dropLast(1).mapNotNull { modKeys[it] }
        val last = parts.last()
        val vk = vkOf(last) ?: modKeys[last]
        if (vk == null) { if (last.length == 1) text(last); return }
        // 押したままの修飾キーは、押し直さず、離さない(Alt を押したまま Tab だけを送れるように)
        val held = heldMods.mapNotNull { modKeys[it] }
        val own = mods.filter { it !in held }
        own.forEach { key(it, true) }
        key(vk, true); key(vk, false)
        own.asReversed().forEach { key(it, false) }
    }
}
