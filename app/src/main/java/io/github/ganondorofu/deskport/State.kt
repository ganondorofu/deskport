package io.github.ganondorofu.deskport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** 画面の姿勢。実機は WindowInfoTracker / ヒンジ角度から自動判定、デバッグ時は手動上書き。 */
enum class Posture(val label: String) {
    BAR("ストレートバー"), FLEX("Flex"), EXPANDED("展開")
}

/** 画面を指で触ったときの意味(1本指)。2本指はどのモードでもズーム/パン。 */
enum class TouchMode(val label: String) {
    VIEW("ビュー"),          // 1本指でビューをパン(リモート操作はしない)
    TAP("タップ"),            // 触った位置にカーソルを置いてクリック(絶対座標)
    SCREEN_PAD("画面パッド"),  // 画面全体をトラックパッドとして使う(相対移動)
}

enum class InputMethod(val label: String) {
    DIRECT("そのまま"),         // キーを1つずつ送る
    BATCH("まとめて"),          // 端末のキーボードで入力して、送信ボタンでまとめて送る
    REALTIME("ライブ"),         // 端末のキーボードで入力して、確定した文字を、すぐ送る
}

enum class KbLayout(val label: String) { US("US配列"), JIS("JIS配列") }

/** キーボードの大きさ。キー数は US配列 / JIS配列 のとき。 */
enum class KbSize(val label: String) {
    COMPACT("コンパクト"),
    TKL("テンキーなし(87/91キー)"),
    FULL("フルサイズ(104/109キー)"),
}

/** 仮想キャンバス: ホストのディスプレイを横に並べた全体。1枚あたりの枠は Stream.WIDTH x Stream.HEIGHT。 */
fun canvasW(): Float = Stream.WIDTH * (Session.host?.displayCount ?: 1).coerceIn(1, 4).toFloat()
const val CANVAS_H = 1080f

/** 接続のあいだ保持する設定(クリップボードの自動同期)。 */
object SessionPrefs {
    var clipboardSync by mutableStateOf(false)
}

/** ファイル転送の設定。 */
object FileSettings {
    var compress by mutableStateOf(false)   // 圧縮して送る・受け取る(通信量の節約。写真や zip など、すでに圧縮されたものは、そのまま)
}

/** キーボードのカスタマイズ設定(Prefs.kt で保存・復元する)。 */
object KbSettings {
    var layout by mutableStateOf(KbLayout.US)
    var size by mutableStateOf(KbSize.COMPACT)
    var inputMethod by mutableStateOf(InputMethod.DIRECT)
    var keyHeight by mutableIntStateOf(1)          // 0=小 1=中 2=大
    var showNumRow by mutableStateOf(true)
    var showFnRow by mutableStateOf(false)
    var showShortcuts by mutableStateOf(true)
    var holdOnDoubleTap by mutableStateOf(true)    // ショートカットを2回連打すると、修飾キー(Alt など)を押したままにする
    var areaH by mutableFloatStateOf(0.42f)        // 横向きのストレートバー: 操作パネルが占める横幅の割合
    var areaVBar by mutableFloatStateOf(0.50f)     // 縦向きのストレートバー: 操作パネルが占める高さの割合
    var areaVWide by mutableFloatStateOf(0.41f)    // 展開: 操作パネルが占める高さの割合
    var padFirst by mutableStateOf(false)          // true=トラックパッドを左(縦並びなら上)に置く
    var splitH by mutableFloatStateOf(0.64f)       // 左右に並べたときの、キーボードが占める割合
    var splitV by mutableFloatStateOf(0.62f)       // 上下に積んだときの、キーボードが占める割合
    val shortcuts = mutableStateListOf("Ctrl+C", "Ctrl+V", "Ctrl+Z", "Alt+Tab", "Esc")
    val presets = listOf(
        "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+Z", "Ctrl+A", "Ctrl+S",
        "Alt+Tab", "Win", "Esc", "Tab", "Del", "Ctrl+Shift+Esc", "Alt+F4", "Win+D", "Win+L", "Win+Tab",
    )
    val heights = listOf(40.dp, 48.dp, 56.dp)
}

/** ポインタ(トラックパッド/画面パッド)の設定。 */
object PointerSettings {
    var speed by mutableFloatStateOf(1.5f)        // 相対移動の倍率
    var accel by mutableStateOf(true)             // 速く動かすほど大きく動く
    var scrollSpeed by mutableFloatStateOf(1.0f)
    // スクロールの向き(true=指に内容がついてくる向き)。入力ごとに、別々に設定できる
    var naturalPad by mutableStateOf(false)      // トラックパッド、統合キーボードの2本指
    var naturalScreen by mutableStateOf(true)    // 画面(タップ・画面パッドのモード)の2本指、マウスのホイール
    var naturalWheel by mutableStateOf(true)     // スクロールホイール
    var cursorScale by mutableFloatStateOf(0.6f)  // 画面上のカーソルの大きさ(倍率)
    var showButtons by mutableStateOf(true)       // トラックパッドの左クリック・右クリックのボタン
    var showWheel by mutableStateOf(true)         // トラックパッドの横のスクロールホイール
    var inertia by mutableStateOf(false)          // 指を離した後も、速度を落としながらカーソルを滑らせる
    var showOffscreenIndicator by mutableStateOf(true)   // 1枚だけ表示しているとき、カーソルが別のディスプレイにあれば、画面の端に知らせる
}

/** 折りたたみスマホ向けの機能(姿勢ごとの設定)を使うか。折りたたみ端末では最初からオン。 */
object DeviceSettings {
    var foldMode by mutableStateOf(false)
}

/** 画質の設定。ビットレート(Mbps)。ディスプレイを複数同時に受けるときは、1枚あたり 6 割に下げる。 */
object StreamSettings {
    var mbps by mutableFloatStateOf(20f)
    var audio by mutableStateOf(true)             // PC の音を再生する(接続のときに決まる)
    var volume by mutableFloatStateOf(1f)         // 端末での音量(0〜1)。PC の音量は変えない
    fun kbps(displays: Int): Int = (mbps * 1000 * (if (displays > 1) 0.6f else 1f)).toInt()
}

/** ビューワーの設定。 */
object ViewerSettings {
    var touchMode by mutableStateOf(TouchMode.VIEW)
    var locked by mutableStateOf(false)           // 固定: ズーム/パンを受け付けない
    var follow by mutableStateOf(false)           // カーソル追従: ズーム中にカーソルを画面中央へ追う
    var resetTick by mutableIntStateOf(0)         // ズームを戻す合図(ヘッダーのボタンから、ビューワーへ伝える)
}

/** リモート側のマウスカーソル(仮想キャンバス座標)。相対移動はこれを動かす。 */
object RemoteCursor {
    var x by mutableFloatStateOf(960f)
    var y by mutableFloatStateOf(540f)
    var dragHide by mutableStateOf(false)   // ドラッグ中は、手元のカーソルを隠す
    var visible by mutableStateOf(true)   // ホストのカーソルが表示中か(自作ホストのとき、ホストが知らせる)

    fun set(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, canvasW()); y = ny.coerceIn(0f, CANVAS_H)
    }

    fun moveBy(d: Offset) = set(x + d.x, y + d.y)
}

/**
 * 直近の操作の内容(キーなど)。画面には出さない。
 */
object Feedback {
    var text by mutableStateOf("")
    var stamp by mutableIntStateOf(0)

    fun show(t: String) { text = t; stamp++ }
}

/** 速度設定と加速を反映した、相対移動量。 */
fun padDelta(d: Offset): Offset {
    val f = if (PointerSettings.accel) 1f + (d.getDistance() / 12f).coerceAtMost(2f) * 0.5f else 1f
    return d * (PointerSettings.speed * f)
}
