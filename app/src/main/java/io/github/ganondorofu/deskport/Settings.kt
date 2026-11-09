package io.github.ganondorofu.deskport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 設定(キーボード/ポインタ)。One UI の「下から出る浮いたダイアログ」の形: 左右と下に余白があり、全周が角丸。
 * (公式ガイド: ユーザー操作が必要なダイアログは画面下部に出す)
 */
/** 設定の1項目。tab=どのタブか、section=見出し、words=検索に使う言葉(項目名に加えて)。 */
private class Entry(val tab: Int, val section: String, val label: String, val words: String = "", val content: @Composable () -> Unit)

private val SettingTabs = listOf("キーボード", "ポインタ", "ジェスチャー", "画質・音声", "折りたたみ", "セキュリティ", "その他")

@Composable
private fun Note(text: String) =
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    var tab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var query by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    ModalBottomSheet(
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,      // 外枠は透明にして、中のカードを浮かせる
        shape = RectangleShape,
        dragHandle = null,
        tonalElevation = 0.dp,
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var dlg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

        val entries: List<Entry> = buildList {
            // ---- キーボード
            add(Entry(0, "キーボード", "配列", "US JIS 日本語 英語 layout") {
                SegmentRow("配列", KbLayout.entries.map { it.label }, KbLayout.entries.indexOf(KbSettings.layout)) { KbSettings.layout = KbLayout.entries[it] }
            })
            add(Entry(0, "キーボード", "キーボードの大きさ", "104 108 109 87 91 テンキー フルサイズ コンパクト") {
                SegmentRow("キーボードの大きさ", KbSize.entries.map { it.label }, KbSize.entries.indexOf(KbSettings.size)) { KbSettings.size = KbSize.entries[it] }
            })
            add(Entry(0, "キーボード", "入力方式", "そのまま まとめて ライブ 直接 一括 即時 日本語 IME batch live direct") {
                SegmentRow("入力方式", InputMethod.entries.map { it.label }, InputMethod.entries.indexOf(KbSettings.inputMethod)) { KbSettings.inputMethod = InputMethod.entries[it] }
            })
            add(Entry(0, "キーボード", "キーの高さ", "サイズ 大きさ") {
                SegmentRow("キーの高さ", listOf("小", "中", "大"), KbSettings.keyHeight) { KbSettings.keyHeight = it }
            })
            add(Entry(0, "キーボード", "修飾キーの2回タップで固定", "Ctrl Alt Shift Win 押しっぱなし ホールド ロック Alt Tab") {
                Column {
                    SwitchRow("修飾キーを2回タップで、押したままにする", KbSettings.holdOnDoubleTap) { KbSettings.holdOnDoubleTap = it }
                    Note("Ctrl、Shift、Alt、Win を2回タップすると固定されます(青く光ります)。例: Alt を固定して、Tab を何度かタップ。固定したキーをもう一度タップすると、離します。")
                }
            })
            add(Entry(0, "キーボード", "数字行を表示") { SwitchRow("数字行を表示", KbSettings.showNumRow) { KbSettings.showNumRow = it } })
            add(Entry(0, "キーボード", "Fキー行を表示", "ファンクション") { SwitchRow("Fキー行を表示", KbSettings.showFnRow) { KbSettings.showFnRow = it } })
            add(Entry(0, "キーボード", "ショートカット行を表示") { SwitchRow("ショートカット行を表示", KbSettings.showShortcuts) { KbSettings.showShortcuts = it } })
            add(Entry(0, "キーボード", "トラックパッドを左(縦並びなら上)に置く", "入れ替え 並び順") {
                SwitchRow("トラックパッドを左(縦並びなら上)に置く", KbSettings.padFirst) { KbSettings.padFirst = it }
            })
            add(Entry(0, "ショートカット", "ショートカットを作る", "自作 追加 組み立て カスタム Ctrl Alt Shift Win") { ShortcutBuilder() })
            add(Entry(0, "ショートカット", "ショートカットの候補", "プリセット Ctrl+C Alt+Tab タスクマネージャー") { ShortcutPresets() })

            // ---- ポインタ
            add(Entry(1, "ポインタ", "トラックパッドの速度", "感度 速さ") {
                SliderRow("トラックパッドの速度", "×${"%.1f".format(PointerSettings.speed)}", PointerSettings.speed, 0.5f..4f) { PointerSettings.speed = it }
            })
            add(Entry(1, "ポインタ", "加速", "速く動かすほど") { SwitchRow("加速(速く動かすほど大きく動く)", PointerSettings.accel) { PointerSettings.accel = it } })
            add(Entry(1, "ポインタ", "スクロールの速度", "ホイール") {
                SliderRow("スクロールの速度", "×${"%.1f".format(PointerSettings.scrollSpeed)}", PointerSettings.scrollSpeed, 0.5f..4f) { PointerSettings.scrollSpeed = it }
            })
            add(Entry(1, "スクロールの向き", "トラックパッド・統合キーボードのスクロール(ナチュラル)", "向き 逆 ナチュラル scroll direction natural") {
                Column {
                    SwitchRow("トラックパッド・統合キーボードのスクロール(ナチュラル)", PointerSettings.naturalPad) { PointerSettings.naturalPad = it }
                    Note("オン: 指の動きに、内容がついてきます。オフ: マウスのホイールと同じ向きです。入力ごとに、別々に決められます。")
                }
            })
            add(Entry(1, "スクロールの向き", "画面のスクロール(ナチュラル)", "向き 逆 ナチュラル") { SwitchRow("画面のスクロール(ナチュラル)", PointerSettings.naturalScreen) { PointerSettings.naturalScreen = it } })
            add(Entry(1, "スクロールの向き", "スクロールホイール(ナチュラル)", "向き 逆 ナチュラル ホイール") { SwitchRow("スクロールホイール(ナチュラル)", PointerSettings.naturalWheel) { PointerSettings.naturalWheel = it } })
            add(Entry(1, "ポインタ", "カーソルの大きさ", "サイズ マウスカーソル") {
                SliderRow("カーソルの大きさ", "×${"%.1f".format(PointerSettings.cursorScale)}", PointerSettings.cursorScale, 0.2f..2f) { PointerSettings.cursorScale = it }
            })
            add(Entry(1, "ポインタ", "カーソルの慣性", "滑る") { SwitchRow("カーソルの慣性(速く動かして離すと、少し滑る)", PointerSettings.inertia) { PointerSettings.inertia = it } })
            add(Entry(1, "ポインタ", "別のディスプレイにカーソルがあるときの目印", "インジケーター 矢印 マルチディスプレイ") {
                Column {
                    SwitchRow("別のディスプレイにカーソルがあるとき、矢印で知らせる", PointerSettings.showOffscreenIndicator) { PointerSettings.showOffscreenIndicator = it }
                    Note("ディスプレイを1枚だけ表示しているとき、カーソルが表示していないほうにあれば、その向きの画面の端に矢印を出します。")
                }
            })
            add(Entry(1, "ポインタ", "左クリック・右クリックのボタンを表示", "ボタン 表示 非表示") { SwitchRow("左クリック・右クリックのボタンを表示", PointerSettings.showButtons) { PointerSettings.showButtons = it } })
            add(Entry(1, "ポインタ", "スクロールホイールを表示", "ホイール 表示 非表示") { SwitchRow("スクロールホイールを表示", PointerSettings.showWheel) { PointerSettings.showWheel = it } })

            // ---- ジェスチャー
            add(Entry(2, "ジェスチャー", "3本指のジェスチャーを使う", "3本指 スワイプ ピンチ gesture swipe pinch") {
                Column {
                    SwitchRow("3本指のジェスチャーを使う", GestureSettings.enabled) { GestureSettings.enabled = it }
                    Note("ビュー・タップ・画面パッドの画面、トラックパッド、統合キーボードの上で、3本指で操作したときの動作を、割り当てられます。")
                }
            })
            GTrigger.entries.forEach { trig ->
                add(Entry(2, "割り当て", trig.label, "ジェスチャー 割り当て gesture") {
                    val cur = GestureSettings.map[trig] ?: GAction.NONE
                    ActionRow(trig.label, cur.label, GAction.entries.map { it.label }) { GestureSettings.map[trig] = GAction.entries[it] }
                })
            }
            add(Entry(2, "割り当て", "ジェスチャーを初期値に戻す", "リセット reset") {
                Text("ジェスチャーを初期値に戻す", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { GestureSettings.map.putAll(GestureSettings.defaults()); GestureSettings.enabled = true }.padding(vertical = 14.dp))
            })

            // ---- 画質・音声
            add(Entry(3, "画質", "ビットレート", "画質 帯域 Mbps 軽く 通信量") {
                Column {
                    SliderRow("ビットレート(次の接続から)", "${StreamSettings.mbps.toInt()} Mbps", StreamSettings.mbps, 4f..60f) { StreamSettings.mbps = it }
                    Note("LAN は 20 Mbps 前後、VPN や外出先は 8 Mbps 前後が目安です。複数のディスプレイを同時に見るときは、1枚あたり 6 割になります。")
                }
            })
            add(Entry(3, "音声", "PC の音を再生する", "サウンド オーディオ 音 スピーカー") {
                Column {
                    SwitchRow("PC の音を再生する(次の接続から)", StreamSettings.audio) { StreamSettings.audio = it }
                    Note("PC で再生している音を、そのまま端末で鳴らします。PC の音量や設定は変えません。マイク入力には、まだ対応していません。")
                }
            })
            add(Entry(3, "音声", "音声の診断", "診断 音が出ない 無音 audio diagnostics") { AudioDiagnostics() })
            add(Entry(3, "音声", "音量", "ボリューム 消音 ミュート") {
                SliderRow("音量", "${(StreamSettings.volume * 100).toInt()}%", StreamSettings.volume, 0f..1f) { StreamSettings.volume = it; AudioPlayer.volume = it }
            })

            // ---- 折りたたみ
            add(Entry(4, "折りたたみスマホ", "折りたたみスマホモード", "Fold Flex 姿勢 ヒンジ") {
                Column {
                    SwitchRow("折りたたみスマホモード", DeviceSettings.foldMode) { DeviceSettings.foldMode = it; Profiles.enablePerMode(it) }
                    Note("折りたたみ端末の姿勢(ストレート、フレックス、展開)ごとに、画面の配置や設定を別々に覚えます。折りたたみでない端末では、オフのままで使えます。")
                }
            })
            if (DeviceSettings.foldMode) {
                add(Entry(4, "モードごとの設定", "モードごとに設定を保存", "姿勢") {
                    Column {
                        SwitchRow("モードごとに設定を保存", Profiles.perMode) { Profiles.enablePerMode(it) }
                        Note(
                            if (Profiles.perMode)
                                "ストレート、フレックス、展開のそれぞれで、次の設定を別々に覚えます: キーボードとトラックパッドの表示、ディスプレイの表示(一括/単独)、画面モード、カーソル追従、固定、並び順、各領域の比率。"
                            else "すべてのモードで同じ設定を使い、最後の状態を覚えます。",
                        )
                    }
                })
                add(Entry(4, "モードごとの設定", "いまのモードの設定を初期値に戻す", "リセット") {
                    Text(
                        "いまのモード(${Profiles.activePosture?.label ?: "-"})の設定を、初期値に戻す",
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { Profiles.resetCurrent() }.padding(vertical = 14.dp),
                    )
                })
            }

            // ---- セキュリティ
            add(Entry(5, "セキュリティ", "接続のたびにパスワードを求める", "パスワード PIN ロック 暗証番号 1Password") {
                Column {
                    SwitchRow("接続のたびにパスワードを求める", AppLock.enabled) { dlg = if (it) "set" else "disable" }
                    Note("この端末のアプリを、他の人が開いても PC へつながらないようにします。4桁の数字、または自由な文字列(パスワードマネージャの自動入力に対応)を選べます。")
                }
            })
            if (AppLock.enabled) add(Entry(5, "セキュリティ", "パスワードを変更する") {
                Text(
                    "パスワードを変更する", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { dlg = "change" }.padding(vertical = 14.dp),
                )
            })

            // ---- その他(クリップボード、言語、更新)
            add(Entry(6, "クリップボード", "クリップボードの自動同期", "コピー 貼り付け clipboard") {
                Column {
                    SwitchRow("クリップボードの自動同期", SessionPrefs.clipboardSync) { SessionPrefs.clipboardSync = it }
                    Note("つないでいる間、PC とスマホの、文字のクリップボードを、自動で同期します。")
                }
            })
            add(Entry(6, "ファイル転送", "圧縮して送る・受け取る", "ファイル 転送 圧縮 通信量 節約") {
                Column {
                    SwitchRow("ファイル転送で圧縮する", FileSettings.compress) { FileSettings.compress = it }
                    Note("通信量の節約になります(CPU を少し使います)。写真・動画・zip・PDF など、すでに圧縮されているものは、そのまま送ります。")
                }
            })
            add(Entry(6, "言語 / Language", "言語 / Language", "language 日本語 english 英語") {
                SegmentRow("言語 / Language", listOf("自動 / Auto", "日本語", "English"), listOf("auto", "ja", "en").indexOf(I18n.mode).coerceAtLeast(0)) {
                    I18n.mode = listOf("auto", "ja", "en")[it]
                }
            })
            // ---- 更新
            add(Entry(6, "アップデート", "更新を確認する", "バージョン 版 アップデート") {
                Column {
                    Text("いまの版: ${Updater.currentName(ctx)}", fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp))
                    if (Updater.status.isNotEmpty()) Text(Updater.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    val av = Updater.available
                    Text(
                        if (av != null) "${av.versionName} に更新する" else "更新を確認する",
                        color = if (Updater.busy) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !Updater.busy) {
                            scope.launch { if (av != null) Updater.install(ctx) else Updater.check(ctx) }
                        }.padding(vertical = 14.dp),
                    )
                }
            })
        }

        // 大画面(折りたたみのメイン画面)ではタスクバーがシートの下に被るので、余白を足す
        val bottomGap = if (LocalConfiguration.current.screenWidthDp >= 600) 68.dp else 12.dp
        FocusBlock(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = bottomGap).navigationBarsPadding()) {
            Column(Modifier.heightIn(max = 640.dp).padding(top = 20.dp, bottom = 8.dp)) {
                Text("設定", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 24.dp))
                // 検索(項目名と関連する言葉で、すべてのタブを探す)
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    placeholder = { Text("設定を検索") },
                    leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        androidx.compose.material3.IconButton(onClick = { query = "" }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Close, "消す") }
                    }) else null,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                )
                val searching = query.isNotBlank()
                if (!searching) Row(
                    Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SettingTabs.forEachIndexed { i, name -> PillTab(name, i == tab, { tab = i }) }
                }
                val shown = if (searching) {
                    val q = query.trim().lowercase()
                    entries.filter { (tr(it.label) + " " + it.label + " " + it.words + " " + tr(it.section) + " " + tr(SettingTabs[it.tab])).lowercase().contains(q) }
                } else entries.filter { it.tab == tab }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    if (shown.isEmpty()) Text("「$query」に合う設定はありません", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    // 同じ見出しの項目は、1つのカードにまとめる(検索結果でも同じ)
                    var idx = 0
                    while (idx < shown.size) {
                        val sec = shown[idx].section
                        val group = shown.drop(idx).takeWhile { it.section == sec }
                        idx += group.size
                        SubHeader(if (searching) "$sec(${SettingTabs[group[0].tab]})" else sec)
                        SettingGroup {
                            group.forEachIndexed { gi, e -> if (gi > 0) RowDivider(); e.content() }
                        }
                    }
                    // One UI のダイアログ下部の、フラットなテキストボタン
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "閉じる", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                            modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(horizontal = 32.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        when (dlg) {
            "set" -> PasswordSetup(onDone = { dlg = "" }, onCancel = { dlg = "" })
            "disable" -> PasswordPrompt("パスワードをオフにする", onOk = { AppLock.disable(); dlg = "" }, onCancel = { dlg = "" })
            "change" -> PasswordPrompt("現在のパスワード", onOk = { dlg = "set" }, onCancel = { dlg = "" })
        }
    }
}

/** ショートカットを組み立てる: 1) 修飾キーを選ぶ(いくつでも) 2) キーを1つ選ぶ 3) 追加。自作したものは、タップで削除できる。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutBuilder() {
    var mods by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
    var key by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var cat by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val cats = listOf(
        "英字" to ('A'..'Z').map { it.toString() },
        "数字" to ('0'..'9').map { it.toString() },
        "F" to (1..12).map { "F$it" },
        "操作" to listOf("Enter", "Esc", "Tab", "Space", "Backspace", "Del", "Ins", "Home", "End", "PgUp", "PgDn", "←", "↑", "→", "↓", "PrtSc"),
        "記号" to "`-=[]\\;',./".map { it.toString() },
    )
    val order = listOf("Ctrl", "Shift", "Alt", "Win")
    val combo = (order.filter { it in mods } + listOfNotNull(key)).joinToString("+")
    Column {
        Text("1. 修飾キー(いくつでも)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            order.forEach { m -> KeyChip(m, m in mods) { mods = if (m in mods) mods - m else mods + m } }
        }
        Text("2. キー", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cats.forEachIndexed { i, (name, _) -> PillTab(name, i == cat, { cat = i }) }
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cats[cat].second.forEach { k -> KeyChip(k, key == k) { key = if (key == k) null else k } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (combo.isEmpty()) "3. 修飾キーとキーを選んでください" else "3. $combo",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(
                onClick = { if (combo !in KbSettings.shortcuts) KbSettings.shortcuts.add(combo); mods = emptySet(); key = null },
                enabled = key != null && RemoteInput.validCombo(combo),
            ) { Text("追加") }
        }
        val own = KbSettings.shortcuts.filter { it !in KbSettings.presets }
        if (own.isNotEmpty()) {
            Text("自作(タップで削除)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            FlowRow(Modifier.padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                own.forEach { p -> KeyChip(p, true) { KbSettings.shortcuts.remove(p) } }
            }
        }
    }
}

/** 候補のショートカット(タップで、行に追加/削除)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutPresets() {
    Column {
        Text("候補(タップで追加/削除)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        FlowRow(Modifier.padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KbSettings.presets.forEach { p ->
                val on = p in KbSettings.shortcuts
                KeyChip(p, on) { if (on) KbSettings.shortcuts.remove(p) else KbSettings.shortcuts.add(p) }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.sp,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 音が出ないときの切り分け: PC から届いているか、端末の音量、出力先を、1秒ごとに更新して見せる。 */
@Composable
private fun AudioDiagnostics() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var perSec by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var ago by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(-1L) }
    var mediaVol by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var route by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("-") }
    var playing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var last = AudioPlayer.received.get()
        while (true) {
            kotlinx.coroutines.delay(1000)
            val now = AudioPlayer.received.get()
            perSec = now - last; last = now
            ago = if (AudioPlayer.lastAt > 0) (System.currentTimeMillis() - AudioPlayer.lastAt) / 1000 else -1L
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            mediaVol = (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
            route = AudioPlayer.routedName(); playing = AudioPlayer.isPlaying
        }
    }
    val connected = Stream.state == Stream.State.STREAMING
    val verdict = when {
        !StreamSettings.audio -> "音声がオフです。「PC の音を再生する」をオンにして、つなぎ直してください"
        !connected -> "PC につながっていません"
        perSec <= 0L && (ago < 0 || ago > 3) -> "PC から音が届いていません。PC で音を鳴らしているか(無音のときは、届きません)、ホストの窓の「状態」で接続を確認してください。音声をオンにしたあとは、つなぎ直しが要ります"
        mediaVol == 0 -> "音は届いていますが、端末のメディア音量が 0 です"
        StreamSettings.volume <= 0f -> "音は届いていますが、アプリの音量が 0 です"
        !playing -> "音は届いていますが、再生が始まっていません"
        else -> "正常に再生しています"
    }
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(verdict, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (verdict == "正常に再生しています") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text("受信: 毎秒 $perSec パケット(最後の受信: ${if (ago < 0) "なし" else "$ago 秒前"})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("端末のメディア音量: ${if (mediaVol < 0) "-" else "$mediaVol%"} · アプリの音量: ${(StreamSettings.volume * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("再生: ${if (playing) "再生中" else "停止"} · 出力先: $route", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 値をタップすると、選択肢のメニューが出る行。 */
@Composable
private fun ActionRow(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
            Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); open = false })
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        OneUiSwitch(checked, onChange)
    }
}

@Composable
private fun SegmentRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { i, l -> PillTab(l, i == selected, { onSelect(i) }) }
        }
    }
}

@Composable
private fun SliderRow(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        OneUiSlider(value, onChange, range, Modifier.padding(top = 4.dp))
    }
}
