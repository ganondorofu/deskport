package io.github.ganondorofu.deskport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.hypot

/** 画面に出すものの状態(キーボード/トラックパッドの表示、どのディスプレイを見ているか)。モード別の設定にも含まれる。 */
object LayoutState {
    var kbVisible by mutableStateOf(true)
    var padVisible by mutableStateOf(true)
    /** キーボードとトラックパッドを1つにした表示(キーボードの上でスワイプするとカーソルが動く)。オンのときは、キーボードとトラックパッドの表示を置き換える。 */
    var hybridVisible by mutableStateOf(false)
    /** -1=全ディスプレイを一括 / 0以上=そのディスプレイだけ */
    var selected by mutableIntStateOf(-1)
}

/**
 * 1つのモードの設定の束。ここに入れたものが、「モードごとに保存」がオンのとき、モードごとに別々に覚えられる。
 * (キーボード/パッドの表示、ディスプレイの表示、画面モード、追従、固定、並び順、各領域の比率)
 */
class ModeProfile(
    val kbVisible: Boolean, val padVisible: Boolean, val selected: Int,
    val touchMode: TouchMode, val follow: Boolean, val locked: Boolean,
    val padFirst: Boolean, val splitH: Float, val splitV: Float,
    val areaH: Float, val areaVBar: Float, val areaVWide: Float,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kb", kbVisible).put("pad", padVisible).put("sel", selected)
        .put("mode", touchMode.ordinal).put("follow", follow).put("locked", locked)
        .put("padFirst", padFirst).put("splitH", splitH.toDouble()).put("splitV", splitV.toDouble())
        .put("areaH", areaH.toDouble()).put("areaVBar", areaVBar.toDouble()).put("areaVWide", areaVWide.toDouble())

    companion object {
        fun fromJson(o: JSONObject, base: ModeProfile): ModeProfile = ModeProfile(
            o.optBoolean("kb", base.kbVisible), o.optBoolean("pad", base.padVisible), o.optInt("sel", base.selected),
            TouchMode.entries.getOrElse(o.optInt("mode", base.touchMode.ordinal)) { base.touchMode },
            o.optBoolean("follow", base.follow), o.optBoolean("locked", base.locked),
            o.optBoolean("padFirst", base.padFirst),
            o.optDouble("splitH", base.splitH.toDouble()).toFloat().coerceIn(0.25f, 0.8f),
            o.optDouble("splitV", base.splitV.toDouble()).toFloat().coerceIn(0.25f, 0.8f),
            o.optDouble("areaH", base.areaH.toDouble()).toFloat().coerceIn(0.2f, 0.7f),
            o.optDouble("areaVBar", base.areaVBar.toDouble()).toFloat().coerceIn(0.2f, 0.75f),
            o.optDouble("areaVWide", base.areaVWide.toDouble()).toFloat().coerceIn(0.2f, 0.75f),
        )
    }
}

/**
 * モード(ストレート/フレックス/展開)ごとの設定の管理。
 *  - perMode=オン: モードが変わるたびに、いまの値をそのモードの束に保存し、新しいモードの束を読み込む。
 *  - perMode=オフ: 値は全モード共通。最後の状態がそのまま保存される。
 * 画面で使う値は、いつも本来の置き場(KbSettings, ViewerSettings, LayoutState)にある。ここは出し入れだけを行う。
 */
object Profiles {
    var perMode by mutableStateOf(true)
    /** 設定中のモード(設定画面に表示するため) */
    var activePosture by mutableStateOf<Posture?>(null)
    val store = HashMap<Posture, ModeProfile>()

    /** いまの画面の状態をひとまとめにして取り出す。 */
    fun capture() = ModeProfile(
        LayoutState.kbVisible, LayoutState.padVisible, LayoutState.selected,
        ViewerSettings.touchMode, ViewerSettings.follow, ViewerSettings.locked,
        KbSettings.padFirst, KbSettings.splitH, KbSettings.splitV,
        KbSettings.areaH, KbSettings.areaVBar, KbSettings.areaVWide,
    )

    private fun apply(p: ModeProfile) {
        LayoutState.kbVisible = p.kbVisible; LayoutState.padVisible = p.padVisible; LayoutState.selected = p.selected
        ViewerSettings.touchMode = p.touchMode; ViewerSettings.follow = p.follow; ViewerSettings.locked = p.locked
        KbSettings.padFirst = p.padFirst; KbSettings.splitH = p.splitH; KbSettings.splitV = p.splitV
        KbSettings.areaH = p.areaH; KbSettings.areaVBar = p.areaVBar; KbSettings.areaVWide = p.areaVWide
    }

    /**
     * 初めてそのモードになったときの値。
     *  - ストレート(細い画面): ディスプレイは1枚、トラックパッドは出さず、画面全体をパッドにして、カーソルを常に追従する。
     *  - フレックス/展開: ディスプレイを全部同時に出し、キーボードとトラックパッドを両方出す(画面モードや追従は、いまの値のまま)。
     * 領域の比率は、いまの値を引き継ぐ(調整済みの値を失わないため)。
     */
    fun defaultFor(p: Posture): ModeProfile {
        val cur = capture()
        return when (p) {
            Posture.BAR -> ModeProfile(true, false, 0, TouchMode.SCREEN_PAD, true, false, false, cur.splitH, cur.splitV, cur.areaH, cur.areaVBar, cur.areaVWide)
            // 画面モード、追従、固定は、いまの値を引き継ぐ(使い慣れた操作を、勝手に変えない)
            else -> ModeProfile(true, true, -1, cur.touchMode, cur.follow, cur.locked, false, cur.splitH, cur.splitV, cur.areaH, cur.areaVBar, cur.areaVWide)
        }
    }

    /** モードに入った(起動直後、折りたたみ/展開、姿勢の手動指定)。 */
    fun enter(p: Posture) {
        val old = activePosture
        if (perMode) {
            if (old != null && old != p) store[old] = capture()
            apply(store[p] ?: defaultFor(p))
        }
        activePosture = p
    }

    /** 保存の直前に呼ぶ: いまの値を、いまのモードの束に写す。 */
    fun syncActive() {
        val p = activePosture ?: return
        if (perMode) store[p] = capture()
    }

    /** 「モードごとに保存」の切り替え。オンにした瞬間は、いまの見た目をそのモードの束として覚える(見た目は変わらない)。 */
    fun enablePerMode(on: Boolean) {
        perMode = on
        if (on) activePosture?.let { store[it] = capture() }
    }

    /** 設定中のモードの束を、初期値に戻す。 */
    fun resetCurrent() {
        val p = activePosture ?: return
        val d = defaultFor(p)
        apply(d)
        if (perMode) store[p] = d
    }
}

/**
 * カーソルの慣性。指を速く動かして離したとき、速度を落としながらカーソルを滑らせる。
 * 離す直前の動きだけを見て、滑らせるかどうかを決める。次のどれかで離したときは、滑らせない(狙った場所に止めやすくするため):
 *  - 指を止めてから離した(最後の動きから、STOP_GAP_MS 以上たっている)
 *  - 減速しながら離した(離す直前の速さが、その前の速さの SLOW_RATIO を下回る)
 *  - ゆっくり離した(離す直前の速さが、MIN_SPEED 未満)
 * 速さは、速度の倍率と加速を掛けた後の移動量から出す。
 */
object CursorInertia {
    private const val TAU_MS = 260f          // 速度が 1/e に減る時間
    private const val MIN_SPEED = 0.35f      // 離す直前の速さ(px/ms)がこれ未満なら、滑らせない
    private const val MAX_SPEED = 6f
    private const val STOP_GAP_MS = 45       // 最後の動きから、これ以上たって離したら、滑らせない
    private const val RECENT_MS = 40         // 「離す直前」とみなす時間
    private const val PRIOR_MS = 70          // その前の、比べる時間
    private const val SLOW_RATIO = 0.55f     // 離す直前の速さが、その前の速さのこの割合より遅ければ、減速とみなす
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Sample(val t: Long, val dx: Float, val dy: Float)
    private val samples = ArrayDeque<Sample>()
    @Volatile private var job: Job? = null

    /** 指が触れた: 滑っていたら止める。 */
    fun down() { job?.cancel(); job = null; samples.clear() }

    /** 指が動いた(d は、速度などを掛けた後の移動量、t は、そのイベントの時刻 ms)。 */
    fun track(d: Offset, t: Long) {
        job?.cancel(); job = null
        samples.addLast(Sample(t, d.x, d.y))
        while (samples.isNotEmpty() && samples.first().t < t - (RECENT_MS + PRIOR_MS + 30)) samples.removeFirst()
    }

    /** 指が離れた。 */
    fun release(upT: Long) {
        if (!PointerSettings.inertia) { samples.clear(); return }
        if (samples.isEmpty()) return
        val last = samples.last().t
        if (upT - last > STOP_GAP_MS) { samples.clear(); return }   // 止めてから離した
        // 離す直前(last-RECENT_MS, last]と、その前(last-RECENT_MS-PRIOR_MS, last-RECENT_MS]の移動量
        var rx = 0f; var ry = 0f; var px = 0f; var py = 0f
        for (s in samples) {
            if (s.t > last - RECENT_MS) { rx += s.dx; ry += s.dy }
            else if (s.t > last - RECENT_MS - PRIOR_MS) { px += s.dx; py += s.dy }
        }
        samples.clear()
        val recent = hypot(rx, ry) / RECENT_MS
        val prior = hypot(px, py) / PRIOR_MS
        if (recent < MIN_SPEED) return                              // ゆっくり離した
        if (prior > MIN_SPEED && recent < prior * SLOW_RATIO) return // 減速して離した
        val k = if (recent > MAX_SPEED) MAX_SPEED / recent else 1f
        var x = rx / RECENT_MS * k; var y = ry / RECENT_MS * k
        job = scope.launch {
            var lastNs = System.nanoTime()
            while (isActive) {
                delay(8)
                val now = System.nanoTime()
                val dt = (now - lastNs) / 1_000_000f; lastNs = now
                RemoteInput.move(Offset(x * dt, y * dt))
                val decay = exp(-dt / TAU_MS)
                x *= decay; y *= decay
                if (hypot(x, y) < 0.02f) break
            }
        }
    }
}
