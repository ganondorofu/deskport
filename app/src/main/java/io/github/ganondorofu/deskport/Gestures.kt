package io.github.ganondorofu.deskport

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEvent
import kotlin.math.abs
import kotlin.math.ln

/** 3本指のジェスチャー(それぞれに、動作を割り当てられる)。 */
enum class GTrigger(val label: String) {
    SWIPE_LEFT("3本指で左へスワイプ"),
    SWIPE_RIGHT("3本指で右へスワイプ"),
    SWIPE_UP("3本指で上へスワイプ"),
    SWIPE_DOWN("3本指で下へスワイプ"),
    PINCH_OUT("3本指でピンチアウト(広げる)"),
    PINCH_IN("3本指でピンチイン(狭める)"),
}

/** ジェスチャーで、できる動作。 */
enum class GAction(val label: String) {
    NONE("なし"),
    PREV_DISPLAY("前のディスプレイ"),
    NEXT_DISPLAY("次のディスプレイ"),
    ALL_DISPLAYS("全ディスプレイを並べる"),
    ZOOM_IN("拡大"),
    ZOOM_OUT("縮小"),
    ZOOM_RESET("ズームを戻す"),
    TOGGLE_KEYBOARD("キーボードの表示/非表示"),
    TOGGLE_PAD("トラックパッドの表示/非表示"),
    TOGGLE_HYBRID("統合キーボードの表示/非表示"),
    ALT_TAB("Alt+Tab(ウィンドウの切り替え)"),
    TASK_VIEW("Win+Tab(タスクビュー)"),
    SHOW_DESKTOP("Win+D(デスクトップを表示)"),
    ESCAPE("Esc"),
}

object GestureSettings {
    var enabled by mutableStateOf(true)
    val map = mutableStateMapOf<GTrigger, GAction>().apply { putAll(defaults()) }

    fun defaults(): Map<GTrigger, GAction> = mapOf(
        GTrigger.SWIPE_LEFT to GAction.NEXT_DISPLAY,
        GTrigger.SWIPE_RIGHT to GAction.PREV_DISPLAY,
        GTrigger.SWIPE_UP to GAction.TASK_VIEW,
        GTrigger.SWIPE_DOWN to GAction.SHOW_DESKTOP,
        GTrigger.PINCH_OUT to GAction.ZOOM_IN,
        GTrigger.PINCH_IN to GAction.ZOOM_OUT,
    )
}

/** ビューワーのズームを、ジェスチャーなど、外から動かすための合図(Viewer が受け取る)。 */
object ZoomCommand {
    var tick by mutableIntStateOf(0)
    var factor by mutableFloatStateOf(1f)   // 掛ける倍率。0 ならリセット
    fun send(f: Float) { factor = f; tick++ }
}

object GestureActions {
    fun run(a: GAction) {
        val n = Session.host?.displayCount ?: 1
        when (a) {
            GAction.NONE -> {}
            GAction.PREV_DISPLAY -> stepDisplay(-1, n)
            GAction.NEXT_DISPLAY -> stepDisplay(+1, n)
            GAction.ALL_DISPLAYS -> LayoutState.selected = -1
            GAction.ZOOM_IN -> ZoomCommand.send(1.6f)
            GAction.ZOOM_OUT -> ZoomCommand.send(1f / 1.6f)
            GAction.ZOOM_RESET -> ZoomCommand.send(0f)
            GAction.TOGGLE_KEYBOARD -> { LayoutState.hybridVisible = false; LayoutState.kbVisible = !LayoutState.kbVisible }
            GAction.TOGGLE_PAD -> { LayoutState.hybridVisible = false; LayoutState.padVisible = !LayoutState.padVisible }
            GAction.TOGGLE_HYBRID -> { LayoutState.hybridVisible = !LayoutState.hybridVisible; if (LayoutState.hybridVisible) LayoutState.kbVisible = true }
            GAction.ALT_TAB -> RemoteInput.combo("Alt+Tab")
            GAction.TASK_VIEW -> RemoteInput.combo("Win+Tab")
            GAction.SHOW_DESKTOP -> RemoteInput.combo("Win+D")
            GAction.ESCAPE -> RemoteInput.combo("Esc")
        }
    }

    /** 表示を、「全部 → 1枚目 → 2枚目 …」の順に、1つ動かす(端で、くるりと回る)。 */
    private fun stepDisplay(d: Int, n: Int) {
        if (n <= 1) return
        val order = listOf(-1) + (0 until n).toList()
        val i = order.indexOf(LayoutState.selected).coerceAtLeast(0)
        LayoutState.selected = order[(i + d + order.size) % order.size]
    }
}

/**
 * 3本指のジェスチャーの判定。3本以上の指が触れている間、feed() に、イベントを渡して、離したら finish() で、動作を実行する。
 *  - 指の中心が、一定以上動いたら、スワイプ(縦横の大きいほうの向き)
 *  - 指の間隔が、一定以上変わったら、ピンチ(スワイプより先に判定)
 */
class GestureTracker {
    private var zoom = 1f
    private var dx = 0f
    private var dy = 0f
    private var active = false

    fun feed(ev: PointerEvent) {
        active = true
        val z = ev.calculateZoom()
        if (z > 0f) zoom *= z
        val p = ev.calculatePan()
        dx += p.x; dy += p.y
    }

    /** 離したとき。3本指の操作があったなら、動作を実行して true。 */
    fun finish(): Boolean {
        if (!active) return false
        val trig = when {
            abs(ln(zoom)) > 0.22f -> if (zoom > 1f) GTrigger.PINCH_OUT else GTrigger.PINCH_IN
            maxOf(abs(dx), abs(dy)) > 90f -> if (abs(dx) > abs(dy)) (if (dx < 0) GTrigger.SWIPE_LEFT else GTrigger.SWIPE_RIGHT) else (if (dy < 0) GTrigger.SWIPE_UP else GTrigger.SWIPE_DOWN)
            else -> null
        }
        reset()
        if (trig != null && GestureSettings.enabled) GestureActions.run(GestureSettings.map[trig] ?: GAction.NONE)
        return true
    }

    fun reset() { zoom = 1f; dx = 0f; dy = 0f; active = false }
}
