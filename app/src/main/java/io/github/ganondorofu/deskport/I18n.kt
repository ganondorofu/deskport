package io.github.ganondorofu.deskport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/**
 * 言語(日本語 / English)。画面の文字は、日本語で書いてあり、英語のときだけ、表示する直前に、この辞書で置き換える。
 *  - 設定の「言語」: 自動(端末の言語。日本語以外は英語)/ 日本語 / English
 *  - Text() は、このパッケージの Text(下)を使う。中で tr() を通すので、Text("設定") のように書くだけで、英語になる。
 *  - 変数が入る文は、{0} {1} の形で書く(例: "音量 {0}%")。入った値(日本語のラベルなど)も、さらに訳す。
 *  - 辞書にない日本語は、そのまま表示する(日本語のまま出るだけで、動作には影響しない)。
 */
object I18n {
    /** auto / ja / en */
    var mode by mutableStateOf("auto")

    val en: Boolean get() = mode == "en" || (mode == "auto" && Locale.getDefault().language != "ja")
}

private val JP = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff\\uff00-\\uffef]")

/** 英語のとき、日本語の文を英語にする。日本語を含まない文は、そのまま返す。 */
fun tr(s: String): String {
    if (!I18n.en || s.isEmpty() || !JP.containsMatchIn(s)) return s
    EN[s]?.let { return it }
    // 変数が入る文: "音量 {0}%" など(入った値も、さらに訳す)
    for ((re, en) in TEMPLATES) {
        val m = re.matchEntire(s) ?: continue
        var out = en
        for (i in 1 until m.groupValues.size) out = out.replace("{${i - 1}}", tr(m.groupValues[i]))
        return out
    }
    // 文の一部だけが、辞書にある(つなげた文など): 長いものから、置き換える
    var out = s
    for ((ja, en) in PHRASES) if (out.contains(ja)) out = out.replace(ja, en)
    return out
}

private val TEMPLATES: List<Pair<Regex, String>> by lazy {
    EN.filterKeys { it.contains("{0}") }.map { (ja, en) ->
        val parts = Regex("\\{\\d\\}").split(ja).map { Regex.escape(it) }
        Regex("^" + parts.joinToString("(.+?)") + "$", RegexOption.DOT_MATCHES_ALL) to en
    }.sortedByDescending { it.first.pattern.length }
}

private val PHRASES: List<Pair<String, String>> by lazy {
    EN.filterKeys { !it.contains("{0}") && it.length >= 2 }.toList().sortedByDescending { it.first.length }
}

/** material3 の Text と同じ。ただし、表示する直前に、tr() を通す。 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    tr(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign,
    lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style,
)

/** 日本語 → 英語。キーボードの JIS のキー(かな、変換 など)と、検索用の言葉は、訳さない。 */
private val EN: Map<String, String> = mapOf(
    // ---- 一般
    "設定" to "Settings", "閉じる" to "Close", "保存" to "Save", "削除" to "Delete", "追加" to "Add", "キャンセル" to "Cancel",
    "消す" to "Clear", "送信" to "Send", "転送" to "Transfer", "名前" to "Name", "鍵" to "Key", "ポート" to "Port",
    "更新" to "Update", "完了" to "Done", "失敗" to "Failed", "原因不明" to "Unknown cause", "確認中…" to "Checking…",
    "読み込み中…" to "Loading…", "読み込めません" to "Cannot load", "接続中" to "Connected", "待機中" to "Idle",
    "未接続" to "Not connected", "切断" to "Disconnect", "メニュー" to "Menu", "もう一度" to "Again",
    // ---- ホスト一覧・編集
    "ホスト" to "Hosts", "ホストがありません" to "No hosts", "接続先を選んでください" to "Choose a host to connect to",
    "QRで登録" to "Register with QR", "手入力" to "Manual", "ホストを追加" to "Add host", "ホストを編集" to "Edit host",
    "アドレス(IP または名前)" to "Address (IP or name)", "別のアドレス(カンマ区切り)" to "Other addresses (comma-separated)",
    "ディスプレイ数" to "Number of displays", "長押しで編集・削除" to "Long-press to edit or delete",
    "PC で Deskport ホストを起動すると QR コードが出ます。「QRで登録」で読み取ってください。" to "Start the Deskport host on your PC to get a QR code, then scan it with “Register with QR”.",
    "登録しました" to "Registered", "このアプリのQRコードではありません" to "This is not a QR code for this app",
    "読み取りできませんでした: {0}" to "Could not scan: {0}",
    "応答あり" to "Responding", "応答なし(PC のホストが動いているか、QRで登録し直してください)" to "No response (check that the PC host is running, or register again with the QR)",
    "映像・操作: " to "Video & control: ",
    // ---- 接続・配信
    "接続しています…" to "Connecting…", "接続できません: {0}" to "Cannot connect: {0}", "映像が途絶えました" to "Video stopped", "映像なし" to "No video",
    "ディスプレイ {0} に切り替えています…" to "Switching to display {0}…",
    "映像のデコーダを用意できません" to "Cannot set up the video decoder",
    "PC とスマホの時計が {0} 秒ずれています(60 秒以内に合わせてください)" to "The clocks of the PC and phone differ by {0} s (set them within 60 s)",
    "ホストから応答がありません(Deskport ホストが動いているか、鍵(QRの登録)とファイアウォールを確認)" to "No response from the host (check that the Deskport host is running, and the key (QR registration) and firewall)",
    "受信エラー: {0}" to "Receive error: {0}", "復号できません(鍵が違う可能性があります)" to "Cannot decrypt (the key may be wrong)",
    "{0} に接続中" to "Connected to {0}", "タップでアプリに戻る" to "Tap to return to the app",
    "接続 {0}秒 · {1}" to "Connected {0}s · {1}",
    // ---- 姿勢・モード・入力方式
    "ストレートバー" to "Straight", "展開" to "Expanded", "ビュー" to "View", "タップ" to "Tap", "画面パッド" to "Screen pad",
    "一括" to "All", "そのまま" to "Direct", "まとめて" to "Batch", "ライブ" to "Live", "画面" to "Screen",
    "US配列" to "US layout", "JIS配列" to "JIS layout", "コンパクト" to "Compact",
    "テンキーなし(87/91キー)" to "Tenkeyless (87/91 keys)", "フルサイズ(104/109キー)" to "Full size (104/109 keys)",
    // ---- アプリバー・メニュー
    "キーボードの表示" to "Show keyboard", "トラックパッドの表示" to "Show trackpad",
    "キーボードとトラックパッドを1つにして表示" to "Combined keyboard and trackpad",
    "ビューワーを固定" to "Lock viewer", "カーソル追従" to "Follow cursor", "カーソルの大きさ" to "Cursor size", "カーソルの大きさ ×{0}" to "Cursor size ×{0}",
    "クリップボードの自動同期" to "Auto-sync clipboard", "ファイル転送" to "File transfer", "Ctrl+Alt+Del を送る" to "Send Ctrl+Alt+Del",
    "カメラを PC に送る" to "Send camera to PC", "カメラを PC に送っています" to "Sending camera to PC",
    "前面カメラにする" to "Use front camera", "背面カメラにする" to "Use back camera", "カメラを開始できません: {0}" to "Cannot start the camera: {0}",
    "音量" to "Volume", "音量 {0}%" to "Volume {0}%", "音量(音声はオフです)" to "Volume (audio is off)",
    // ---- トラックパッド・キーボード
    "トラックパッド" to "Trackpad", "左クリック" to "Left click", "右クリック" to "Right click", "左" to "Left", "右" to "Right",
    "タップ=左クリック · 2本指タップ=右クリック · 長押ししてドラッグ=ドラッグ&ドロップ" to "Tap = left click · Two-finger tap = right click · Press and hold, then drag = drag & drop",
    "指を離すと、キーボードに戻ります" to "Lift your finger to return to the keyboard",
    "ここに入力してまとめて送信" to "Type here, then send all at once", "入力した文字をすぐ送信" to "Send typed characters immediately",
    "最後に送信: {0}(タップで入力欄に戻す)" to "Last sent: {0} (tap to put it back in the input box)",
    "確定した文字を、すぐ送信(変換が終わった分から送ります)" to "Sends text as soon as it is confirmed (after conversion)",
    "小" to "S", "中" to "M", "大" to "L",
    // ---- 設定: タブ・見出し
    "キーボード" to "Keyboard", "ポインタ" to "Pointer", "画質・音声" to "Quality & Audio", "折りたたみ" to "Foldable", "セキュリティ" to "Security",
    "その他" to "Other", "アップデート" to "Update", "画質" to "Quality", "音声" to "Audio", "折りたたみスマホ" to "Foldable phone",
    "モードごとの設定" to "Per-mode settings", "ショートカット" to "Shortcuts", "設定を検索" to "Search settings",
    "「{0}」に合う設定はありません" to "No settings match “{0}”",
    // ---- 設定: キーボード
    "配列" to "Layout", "キーボードの大きさ" to "Keyboard size", "入力方式" to "Input method", "キーの高さ" to "Key height",
    "数字行を表示" to "Show number row", "Fキー行を表示" to "Show function key row", "ショートカット行を表示" to "Show shortcut row",
    "トラックパッドを左(縦並びなら上)に置く" to "Put trackpad on the left (top when stacked)",
    "修飾キーの2回タップで固定" to "Double-tap modifiers to lock", "修飾キーを2回タップで、押したままにする" to "Double-tap a modifier key to hold it down",
    "Ctrl、Shift、Alt、Win を2回タップすると固定されます(青く光ります)。例: Alt を固定して、Tab を何度かタップ。固定したキーをもう一度タップすると、離します。" to "Tap Ctrl, Shift, Alt or Win twice to lock it (it lights up blue). Example: lock Alt, then tap Tab several times. Tap a locked key again to release it.",
    "ショートカットを作る" to "Create a shortcut", "ショートカットの候補" to "Shortcut suggestions",
    "1. 修飾キー(いくつでも)" to "1. Modifier keys (any number)", "2. キー" to "2. Key", "3. 修飾キーとキーを選んでください" to "3. Choose modifier keys and a key",
    "英字" to "Letters", "数字" to "Digits", "記号" to "Symbols", "操作" to "Controls",
    "自作(タップで削除)" to "Custom (tap to delete)", "候補(タップで追加/削除)" to "Suggestions (tap to add/remove)",
    // ---- 設定: ポインタ
    "トラックパッドの速度" to "Trackpad speed", "加速" to "Acceleration", "加速(速く動かすほど大きく動く)" to "Acceleration (faster swipes move farther)",
    "スクロールの速度" to "Scroll speed", "ナチュラルスクロール" to "Natural scrolling",
    "カーソルの慣性" to "Cursor inertia", "カーソルの慣性(速く動かして離すと、少し滑る)" to "Cursor inertia (glides a little after a fast swipe)",
    "左クリック・右クリックのボタンを表示" to "Show left/right click buttons", "スクロールホイールを表示" to "Show scroll wheel",
    // ---- 設定: 画質・音声
    "ビットレート" to "Bitrate", "ビットレート(次の接続から)" to "Bitrate (from next connection)",
    "LAN は 20 Mbps 前後、VPN や外出先は 8 Mbps 前後が目安です。複数のディスプレイを同時に見るときは、1枚あたり 6 割になります。" to "About 20 Mbps is a good guide on a LAN, and about 8 Mbps on a VPN or away from home. When viewing several displays at once, each gets 60% of this.",
    "PC の音を再生する" to "Play PC audio", "PC の音を再生する(次の接続から)" to "Play PC audio (from next connection)",
    "PC で再生している音を、そのまま端末で鳴らします。PC の音量や設定は変えません。マイク入力には、まだ対応していません。" to "Plays the sound your PC is playing on this device. It does not change your PC's volume or settings. Microphone input is not supported yet.",
    // ---- 設定: 折りたたみ
    "折りたたみスマホモード" to "Foldable phone mode",
    "折りたたみ端末の姿勢(ストレート、フレックス、展開)ごとに、画面の配置や設定を別々に覚えます。折りたたみでない端末では、オフのままで使えます。" to "Remembers layout and settings separately for each posture of a foldable device (Straight, Flex, Expanded). Leave this off on non-foldable devices.",
    "モードごとに設定を保存" to "Save settings per mode", "いまのモードの設定を初期値に戻す" to "Reset current mode settings to defaults",
    "いまのモード({0})の設定を、初期値に戻す" to "Reset settings of the current mode ({0}) to defaults",
    "すべてのモードで同じ設定を使い、最後の状態を覚えます。" to "All modes share the same settings, and the last state is remembered.",
    "ストレート、フレックス、展開のそれぞれで、次の設定を別々に覚えます: キーボードとトラックパッドの表示、ディスプレイの表示(一括/単独)、画面モード、カーソル追従、固定、並び順、各領域の比率。" to "Straight, Flex and Expanded each remember these separately: keyboard and trackpad visibility, display view (all/single), screen mode, cursor follow, lock, order, and panel ratios.",
    // ---- 設定: セキュリティ・パスワード
    "接続のたびにパスワードを求める" to "Ask for a password on every connection", "パスワードを変更する" to "Change password",
    "この端末のアプリを、他の人が開いても PC へつながらないようにします。4桁の数字、または自由な文字列(パスワードマネージャの自動入力に対応)を選べます。" to "Prevents others who open this app on your device from connecting to your PC. Choose a 4-digit number or any string (password manager autofill supported).",
    "接続時のパスワード" to "Connection password", "4桁の数字" to "4-digit number", "自由な文字列" to "Any text",
    "パスワード" to "Password", "パスワード(6文字以上)" to "Password (6+ characters)", "パスワードを入力" to "Enter password",
    "パスワードをオフにする" to "Turn off password", "現在のパスワード" to "Current password", "パスワードが違います" to "Wrong password",
    "4桁で入力してください" to "Enter 4 digits", "6文字以上にしてください" to "Use at least 6 characters", "一致しません" to "Doesn't match",
    "このパスワードは、この端末のアプリを守るためのものです。PC の鍵とは別で、PC には送られません。" to "This password only protects the app on this device. It is separate from the PC key and is never sent to the PC.",
    "間違いが続いたため、{0}秒待ってください" to "Too many wrong attempts. Wait {0} s.",
    // ---- 設定: 更新・その他
    "更新を確認する" to "Check for updates", "{0} に更新する" to "Update to {0}", "いまの版: {0}" to "Current version: {0}",
    "新しい版 {0} があります" to "New version {0} available", "最新の版です({0})" to "You're up to date ({0})",
    "確認できませんでした: {0}" to "Could not check: {0}", "ダウンロード中…" to "Downloading…",
    "インストールの確認を待っています…" to "Waiting for install confirmation…", "更新しました" to "Updated",
    "更新できませんでした: {0}" to "Update failed: {0}",
    "ダウンロードしたファイルが、更新情報と合いません" to "The downloaded file does not match the update info",
    "更新情報の署名が合いません(配布元が改ざんされた可能性があります)" to "The update info signature does not match (the distribution source may have been tampered with)",
    // ---- ファイル転送
    "スマホ → PC" to "Phone -> PC", "PC → スマホ" to "PC -> Phone",
    "ファイルを選ぶ" to "Choose files", "写真・動画を選ぶ" to "Choose photos/videos", "PC で表示" to "Show on PC", "開く" to "Open",
    "再試行" to "Retry", "中止" to "Canceled",
    "圧縮して送る・受け取る" to "Compress when sending/receiving", "ファイル転送で圧縮する" to "Compress file transfers",
    "通信量の節約になります。写真・動画・zip などは、そのまま送ります" to "Saves bandwidth. Photos, videos, zip files and the like are sent as they are",
    "通信量の節約になります(CPU を少し使います)。写真・動画・zip・PDF など、すでに圧縮されているものは、そのまま送ります。" to "Saves bandwidth (uses a little CPU). Already-compressed files such as photos, videos, zip and PDF are sent as they are.",
    "PC からファイルを削除しますか?" to "Delete this file from the PC?",
    "保存先(PC): " to "Saved on PC: ", "保存先: " to "Saved to: ", "最新の一覧を取れませんでした(前回の内容です)" to "Could not fetch the latest list (showing the previous one)",
    "PC につながりません(PC のホストが動いているか、確認してください)" to "Cannot reach the PC (check that the host is running)",
    "キャンセルしました" to "Canceled", "保存先を作れません" to "Cannot create the destination", "開けるアプリがありません" to "No app can open this",
    "PC の Deskport の窓の「ファイル」タブで追加したファイルが、下に出ます。「保存」を押すと、スマホの「ダウンロード/Deskport」に保存されます。" to "Files added in the PC's Deskport window (Files tab) appear below. Tap Save to store them in Download/Deskport on this phone.",
    "別のディスプレイにカーソルがあるときの目印" to "Marker when the cursor is on another display",
    "別のディスプレイにカーソルがあるとき、矢印で知らせる" to "Show an arrow when the cursor is on another display",
    "ディスプレイを1枚だけ表示しているとき、カーソルが表示していないほうにあれば、その向きの画面の端に矢印を出します。" to "When viewing a single display, an arrow appears at the screen edge toward the cursor if it is on a display you are not viewing.",
    "ファイルを選んで送る" to "Choose a file to send", "他のアプリの「共有」からも送れます" to "You can also send from other apps' Share menu",
    "ホストのファイル" to "Files on host", "ファイルはありません" to "No files", "保存: {0}" to "Saved: {0}", "保存先: {0}" to "Save to: {0}",
    "送り先: {0}({1})" to "Send to: {0} ({1})", "ホストが未登録" to "Host not registered",
    "ホストが未登録です。ホスト一覧で追加してください" to "Host not registered. Add one in the host list.",
    "ファイル名が不正です" to "Invalid file name", "大きすぎます" to "Too large", "データが不正です" to "Invalid data",
    "元のファイルが途中で終わりました" to "The source file ended unexpectedly", "不正なフレーム" to "Invalid frame",
    "ホストの応答が不正です" to "Invalid response from host", "空の返事" to "Empty reply",
    "取得できません(ホストエージェントに接続できない)" to "Unavailable (cannot reach host agent)",
    // ---- ジェスチャー・スクロールの向き・クリップボード
    "ジェスチャー" to "Gestures", "割り当て" to "Assignments", "3本指のジェスチャーを使う" to "Use three-finger gestures",
    "ビュー・タップ・画面パッドの画面、トラックパッド、統合キーボードの上で、3本指で操作したときの動作を、割り当てられます。" to "Assign what happens when you use three fingers on the screen (View, Tap, Screen pad), the trackpad, or the combined keyboard.",
    "3本指で左へスワイプ" to "Swipe left with three fingers", "3本指で右へスワイプ" to "Swipe right with three fingers",
    "3本指で上へスワイプ" to "Swipe up with three fingers", "3本指で下へスワイプ" to "Swipe down with three fingers",
    "3本指でピンチアウト(広げる)" to "Pinch out with three fingers", "3本指でピンチイン(狭める)" to "Pinch in with three fingers",
    "ジェスチャーを初期値に戻す" to "Reset gestures to defaults",
    "なし" to "None", "前のディスプレイ" to "Previous display", "次のディスプレイ" to "Next display", "全ディスプレイを並べる" to "Show all displays",
    "拡大" to "Zoom in", "縮小" to "Zoom out", "ズームを戻す" to "Reset zoom",
    "キーボードの表示/非表示" to "Show/hide keyboard", "トラックパッドの表示/非表示" to "Show/hide trackpad", "統合キーボードの表示/非表示" to "Show/hide combined keyboard",
    "Alt+Tab(ウィンドウの切り替え)" to "Alt+Tab (switch windows)", "Win+Tab(タスクビュー)" to "Win+Tab (Task View)", "Win+D(デスクトップを表示)" to "Win+D (show desktop)",
    "スクロールの向き" to "Scroll direction", "トラックパッド・統合キーボードのスクロール(ナチュラル)" to "Trackpad / combined keyboard scrolling (natural)",
    "画面のスクロール(ナチュラル)" to "Screen scrolling (natural)", "スクロールホイール(ナチュラル)" to "Scroll wheel (natural)",
    "オン: 指の動きに、内容がついてきます。オフ: マウスのホイールと同じ向きです。入力ごとに、別々に決められます。" to "On: content follows your finger. Off: same direction as a mouse wheel. Each input can be set separately.",
    "クリップボード" to "Clipboard", "つないでいる間、PC とスマホの、文字のクリップボードを、自動で同期します。" to "While connected, the text clipboards of the PC and the phone are synced automatically.",
    "表示を固定(ズーム・移動をしない)" to "Lock view (no zoom or pan)",
    // ---- 接続の品質・カメラの状態・音声の診断
    " · ロック画面・UAC 対応" to " · Lock screen & UAC", " · 通常の接続に戻しました" to " · Fell back to normal connection",
    "受信 {0} fps · 再接続 {1} 回 · 遅延 {2}" to "Receiving {0} fps · reconnects {1} · latency {2}",
    "カメラの許可がありません(タップで許可)" to "No camera permission (tap to allow)", "接続待ち(PC につながっていません)" to "Waiting for connection (not connected to PC)",
    "送信中" to "Sending", "カメラの映像を待っています" to "Waiting for camera video",
    "音声の診断" to "Audio diagnostics",
    "音声がオフです。「PC の音を再生する」をオンにして、つなぎ直してください" to "Audio is off. Turn on “Play PC audio” and reconnect.",
    "PC につながっていません" to "Not connected to the PC",
    "PC から音が届いていません。PC で音を鳴らしているか(無音のときは、届きません)、ホストの窓の「状態」で接続を確認してください。音声をオンにしたあとは、つなぎ直しが要ります" to "No audio is arriving from the PC. Check that sound is playing on the PC (silence is not sent) and check the connection in the host window's Status tab. After turning audio on, reconnect.",
    "音は届いていますが、端末のメディア音量が 0 です" to "Audio is arriving, but this device's media volume is 0",
    "音は届いていますが、アプリの音量が 0 です" to "Audio is arriving, but the app volume is 0",
    "音は届いていますが、再生が始まっていません" to "Audio is arriving, but playback has not started",
    "正常に再生しています" to "Playing normally",
    "受信: 毎秒 {0} パケット(最後の受信: {1})" to "Received: {0} packets/s (last: {1})",
    "{0} 秒前" to "{0} s ago",
    "端末のメディア音量: {0} · アプリの音量: {1}%" to "Device media volume: {0} · App volume: {1}%",
    "再生: {0} · 出力先: {1}" to "Playback: {0} · Output: {1}", "再生中" to "Playing", "停止" to "Stopped",
    // ---- レイアウト操作
    "入れ替え" to "Swap", "割合を戻す" to "Reset ratio",
)
