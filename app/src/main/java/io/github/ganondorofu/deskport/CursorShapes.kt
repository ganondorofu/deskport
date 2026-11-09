package io.github.ganondorofu.deskport

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

/** ホストのカーソルの形(画像とホットスポット)。busy は読み込み中の輪で、画像の代わりに回転する輪を描く。 */
class CursorShape(
    val id: Int, val w: Int, val h: Int, val hotX: Int, val hotY: Int, val busy: Boolean, val image: ImageBitmap,
)

/**
 * ホストから受け取ったカーソルの形の保存先。形は番号で区別し、一度受け取れば、再び使うときは送られない。
 * 位置のパケットに、いまの形の番号が付いてくる。持っていない番号なら、受信側が再送を頼む。
 */
object CursorShapes {
    private val map = ConcurrentHashMap<Int, CursorShape>()
    @Volatile private var wanted = 0
    /** 全部が透明の形(アプリが「カーソルを隠す」ために使う)。手元では隠さず、矢印を描く(位置が分からなくなるため)。 */
    private val blank = ConcurrentHashMap.newKeySet<Int>()

    /** いま表示するカーソルの形。まだ受け取っていなければ null(矢印を描く)。 */
    var current by mutableStateOf<CursorShape?>(null)

    fun has(id: Int) = map.containsKey(id) || id in blank

    /** ホストが「いまの形はこれ」と言ってきた。持っていれば切り替える。 */
    fun select(id: Int) {
        wanted = id
        if (id in blank) { current = null; return }
        // まだ受け取っていない形は、届くまで矢印にする(古い形のまま固まらないように)
        val s = map[id]
        if (s == null) current = null else if (current !== s) current = s
    }

    fun put(id: Int, w: Int, h: Int, hotX: Int, hotY: Int, busy: Boolean, rgba: ByteArray) {
        if (w <= 0 || h <= 0 || rgba.size < w * h * 4) return
        if ((0 until w * h).all { rgba[it * 4 + 3].toInt() == 0 }) { blank.add(id); if (wanted == id) current = null; return }
        val px = IntArray(w * h) { i ->
            val r = rgba[i * 4].toInt() and 0xFF; val g = rgba[i * 4 + 1].toInt() and 0xFF
            val b = rgba[i * 4 + 2].toInt() and 0xFF; val a = rgba[i * 4 + 3].toInt() and 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val shape = CursorShape(id, w, h, hotX, hotY, busy, bmp.asImageBitmap())
        if (map.size > 64) map.clear()
        map[id] = shape
        if (wanted == id) current = shape
    }

    fun clear() { map.clear(); blank.clear(); current = null; wanted = 0 }
}
