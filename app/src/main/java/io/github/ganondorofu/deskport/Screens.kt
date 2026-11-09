package io.github.ganondorofu.deskport

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * アプリの最上位。接続中のホストがなければホスト一覧、あれば操作画面。
 * 操作画面から「戻る」でホスト一覧に出ても、接続(Session)は切れない。
 */
@Composable
fun RemoteApp(
    autoPosture: Posture, hingeAngle: Float? = null,
    hingeTopPx: Int? = null, hingeBottomPx: Int? = null,   // 水平ヒンジ(折れ目)の位置。Flex時の上下分割に使う
) {
    var showHosts by remember { mutableStateOf(false) }
    // 設定やホスト一覧が変わるたびに保存する
    LaunchedEffect(Unit) { snapshotFlow { Prefs.snapshotKey() }.collect { Prefs.save() } }
    ClipboardSyncEffect()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (Session.host == null || showHosts) HostsScreen(onOpen = { showHosts = false })
        else RemoteScreen(autoPosture, hingeAngle, hingeTopPx, hingeBottomPx, onHosts = { showHosts = true })
    }
    if (Transfers.showSheet) TransferSheet { Transfers.showSheet = false }
}

@Composable
private fun RemoteScreen(
    autoPosture: Posture, hingeAngle: Float?,
    hingeTopPx: Int?, hingeBottomPx: Int?,
    onHosts: () -> Unit,
) {
    BackHandler { onHosts() }
    val ctx = LocalContext.current
    val posture = autoPosture
    // 画面の状態は LayoutState に置く(保存され、モード別の設定にも含まれる)
    val kbVisible = LayoutState.kbVisible
    val padVisible = LayoutState.padVisible
    val selected: Int? = LayoutState.selected.takeIf { it >= 0 }   // null=全ディスプレイ一括 / それ以外=そのディスプレイだけ
    // モードが変わったら(起動直後、折りたたみ/展開)、そのモードの設定を読み込む
    LaunchedEffect(posture) { Profiles.enter(posture) }
    // 見るディスプレイが変わったら(操作でも、モードの切り替えでも)、配信を合わせる
    LaunchedEffect(LayoutState.selected, Stream.state) {
        if (Stream.state == Stream.State.STREAMING) {
            val sel = LayoutState.selected
            if (Stream.native) Stream.showDisplays(if (sel < 0) (0 until (Session.host?.displayCount ?: 1)).toSet() else setOf(sel))
            else if (sel >= 0) Stream.switchDisplay(sel)
        }
    }
    // 1枚だけ見るディスプレイを変えたら、カーソルも、そのディスプレイへ移す(前のディスプレイに、置き去りにしない)
    LaunchedEffect(LayoutState.selected) {
        val sel = LayoutState.selected
        if (sel >= 0 && Stream.state == Stream.State.STREAMING) {
            val k = Stream.displayOf(RemoteCursor.x)
            if (k != sel) {
                val rel = (RemoteCursor.x - k * Stream.WIDTH).coerceIn(0f, Stream.WIDTH - 1f)
                RemoteInput.moveTo(sel * Stream.WIDTH + rel, RemoteCursor.y)
            }
        }
    }
    var settings by remember { mutableStateOf(false) }
    val sent = remember { mutableStateListOf<String>() }

    val appBar: @Composable () -> Unit = {
        AppBar(
            posture = posture, hingeAngle = hingeAngle,
            kbVisible = kbVisible && !LayoutState.hybridVisible, onKeyboard = { LayoutState.hybridVisible = false; LayoutState.kbVisible = !LayoutState.kbVisible },
            padVisible = padVisible && !LayoutState.hybridVisible, onPad = { LayoutState.hybridVisible = false; LayoutState.padVisible = !LayoutState.padVisible },
            hybridVisible = LayoutState.hybridVisible,
            onHybrid = { LayoutState.hybridVisible = !LayoutState.hybridVisible; if (LayoutState.hybridVisible) LayoutState.kbVisible = true },
            selected = selected,
            onSelect = { LayoutState.selected = it ?: -1 },
            onSettings = { settings = true },
            onTransfer = { Transfers.showSheet = true },
            onDisconnect = { Stream.stop(); Session.disconnect(ctx) },
        )
    }
    val view: @Composable (Modifier) -> Unit = { m -> RemoteViewport(m, selected) }

    run {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val boxW = maxWidth
            val boxH = maxHeight          // ColumnScope内から参照できるよう退避
            val density = LocalDensity.current
            val side = OneUi.SideMargin

            if (posture == Posture.FLEX && landscape) {
                // Flex(横向き): 折れ目を境に、上=アプリバー+画面、下=キーボード+トラックパッド。
                // 折れ目の位置は WindowManager の値を優先、取れなければ画面の縦中央(Foldは左右対称に折れる)
                val topDp = hingeTopPx?.let { with(density) { it.toDp() } } ?: (boxH / 2)
                val gapDp = if (hingeTopPx != null && hingeBottomPx != null)
                    with(density) { (hingeBottomPx - hingeTopPx).coerceAtLeast(0).toDp() } else 0.dp
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().height(topDp).statusBarsPadding()) {
                        appBar()
                        view(Modifier.weight(1f).padding(horizontal = 4.dp).padding(bottom = 4.dp))
                    }
                    Spacer(Modifier.height(gapDp))   // 折れ目の厚み部分には何も置かない
                    Controls(
                        kbVisible, padVisible, vertical = false, sent,
                        Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().padding(side),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    appBar()
                    val anyControl = kbVisible || padVisible
                    when {
                        // ストレートバー(横向き): 映像を左、操作パネルを右に
                        posture == Posture.BAR && landscape -> Row(Modifier.fillMaxSize()) {
                            view(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp, bottom = 4.dp))
                            if (anyControl) {
                                // 映像と操作パネルの境界: 長押ししてドラッグで、横幅の割合を変える
                                AreaHandle(horizontalBar = false) { dx ->
                                    KbSettings.areaH = (KbSettings.areaH - with(density) { dx.toDp() }.value / boxW.value).coerceIn(maxOf(0.2f, 240f / boxW.value), 0.7f)
                                }
                                Controls(
                                    kbVisible, padVisible, vertical = true, sent,
                                    Modifier.width(boxW * KbSettings.areaH.coerceAtLeast(maxOf(0.2f, 240f / boxW.value))).fillMaxHeight().padding(end = side, bottom = side),
                                )
                            }
                        }
                        // ストレートバー(縦向き)
                        posture == Posture.BAR -> {
                            view(Modifier.weight(1f).padding(horizontal = 4.dp))
                            if (anyControl) {
                                AreaHandle(horizontalBar = true) { dy ->
                                    KbSettings.areaVBar = (KbSettings.areaVBar - with(density) { dy.toDp() }.value / boxH.value).coerceIn(0.2f, 0.75f)
                                }
                                Controls(
                                    kbVisible, padVisible, vertical = true, sent,
                                    Modifier.fillMaxWidth().height(boxH * KbSettings.areaVBar).padding(start = side, end = side, bottom = side),
                                )
                            }
                        }
                        // 縦向きのFlex(通常は横向きになるため暫定): 上下を等分
                        posture == Posture.FLEX -> {
                            view(Modifier.weight(1f).padding(horizontal = 4.dp))
                            Controls(kbVisible, padVisible, vertical = false, sent, Modifier.weight(1f).padding(side))
                        }
                        // 展開: 画面を横幅いっぱいに使い、下にキーボードとトラックパッド
                        else -> {
                            view(Modifier.weight(1f).padding(horizontal = 4.dp))
                            if (anyControl) {
                                // 映像と操作パネルの境界: 長押ししてドラッグで、高さの割合を変える
                                AreaHandle(horizontalBar = true) { dy ->
                                    KbSettings.areaVWide = (KbSettings.areaVWide - with(density) { dy.toDp() }.value / boxH.value).coerceIn(0.2f, 0.75f)
                                }
                                Controls(
                                    kbVisible, padVisible, vertical = false, sent,
                                    Modifier.fillMaxWidth().height(boxH * KbSettings.areaVWide).padding(start = side, end = side, bottom = side),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (settings) SettingsSheet { settings = false }
}

/**
 * 通常のアプリバー(One UIの"Collapsed app bar")。
 * 画面を主役にするため展開式の大見出しは使わない(公式ガイド: 画面全体をコントローラ等で埋める画面では非対応)。
 * 一括表示と各ディスプレイの切替は、サブタブで行う。
 */
@Composable
private fun AppBar(
    posture: Posture, hingeAngle: Float?,
    kbVisible: Boolean, onKeyboard: () -> Unit,
    padVisible: Boolean, onPad: () -> Unit,
    hybridVisible: Boolean, onHybrid: () -> Unit,
    selected: Int?, onSelect: (Int?) -> Unit,
    onSettings: () -> Unit,
    onTransfer: () -> Unit, onDisconnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // メニューを開いている間だけ、遅延(PC との往復の時間)を測る
    var ping by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(menu) {
        while (menu) {
            val h = Stream.currentHost
            ping = if (h != null && Stream.state == Stream.State.STREAMING) NativeProbe.rtt(h) else -1L
            kotlinx.coroutines.delay(3000)
        }
    }
    var uptime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            uptime = (System.currentTimeMillis() - Session.connectedSince) / 1000
            kotlinx.coroutines.delay(1000)
        }
    }
    var fps by remember { mutableStateOf(0) }
    LaunchedEffect(Stream.state) { if (Stream.state == Stream.State.STREAMING) Stream.fpsLoop { fps = it } else fps = 0 }
    val primary = MaterialTheme.colorScheme.primary
    val plain = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val displays = Session.host?.displayCount ?: 1

    // 左: ホスト名と、配信の状況(fps と形式。映像に被せずに、ここに出す)
    val title: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(Session.host?.name ?: "未接続", fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (Stream.state) {
                    Stream.State.STREAMING -> "$fps fps · ${Stream.primaryDecoder.mime.removePrefix("video/")}" +
                        (if (Stream.usingSecure) " · ロック画面・UAC 対応" else "") + (if (Stream.secureFallback) " · 通常の接続に戻しました" else "")
                    Stream.State.STARTING, Stream.State.FAILED -> Stream.message.ifEmpty { "接続しています…" }
                    else -> "映像なし"
                },
                fontSize = 11.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // ディスプレイの切り替え(一括 / 1 / 2 …)。ディスプレイが1枚だけなら出さない
    val displaySeg: @Composable () -> Unit = {
        if (displays > 1) Row(
            Modifier.clip(CircleShape).background(plain.copy(alpha = 0.08f)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val items = listOf<Pair<String, Int?>>("一括" to null) + (0 until displays).map { "${it + 1}" to it }
            items.forEach { (label, id) ->
                val sel = selected == id
                Text(
                    label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) Color.White else plain, maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(if (sel) primary else Color.Transparent)
                        .clickable { onSelect(id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
    // 右: キーボード / トラックパッド / クリップボード / メニュー
    val actions: @Composable () -> Unit = {
        IconToggleButton(checked = kbVisible, onCheckedChange = { onKeyboard() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Keyboard, "キーボードの表示", tint = if (kbVisible) primary else plain)
        }
        IconToggleButton(checked = padVisible, onCheckedChange = { onPad() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Mouse, "トラックパッドの表示", tint = if (padVisible) primary else plain)
        }
        IconToggleButton(checked = hybridVisible, onCheckedChange = { onHybrid() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.TouchApp, "キーボードとトラックパッドを1つにして表示", tint = if (hybridVisible) primary else plain)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "メニュー") }
            // One UI のポップアップメニュー: 白い角丸、色味のない背景
            DropdownMenu(
                menu, { menu = false },
                shape = RoundedCornerShape(OneUi.RadiusMedium),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp, shadowElevation = 12.dp,
            ) {
                Text(
                    "接続 ${uptime}秒 · ${posture.label}" + (hingeAngle?.let { " · ${it.toInt()}°" } ?: ""),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), fontSize = 12.sp, color = sub,
                )
                Text(
                    "受信 $fps fps · 再接続 ${Stream.reconnects} 回 · 遅延 ${if (ping >= 0) "$ping ms" else "-"}",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), fontSize = 12.sp, color = sub,
                )
                ViewerMenuItems()
                CameraMenuItems(onDone = { menu = false })
                // PC で、ロック画面・UAC への対応が有効なときだけ出る(つながったときに、自動で選ばれる)
                if (Stream.usingSecure) DropdownMenuItem(
                    text = { Text("Ctrl+Alt+Del を送る") },
                    enabled = Stream.state == Stream.State.STREAMING,
                    onClick = { Stream.nativeInput?.sas(); menu = false },
                )
                androidx.compose.material3.HorizontalDivider()
                DropdownMenuItem(text = { Text("ファイル転送") }, onClick = { onTransfer(); menu = false })
                DropdownMenuItem(text = { Text("設定") }, onClick = { onSettings(); menu = false })
                DropdownMenuItem(text = { Text("切断") }, onClick = { onDisconnect(); menu = false })
            }
        }
    }

    // 展開した画面では、ディスプレイ切り替えも操作の欄(キーボード等の表示切替)と同じ1段にまとめ、映像の領域を最大にする。
    // それより狭い画面(折りたたみ時)では、操作を2段目に回す
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = OneUi.TitleMargin, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                title(Modifier.weight(1f))
                displaySeg(); ViewerHeaderControls(); actions()
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = OneUi.TitleMargin, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    title(Modifier.weight(1f)); actions()
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = OneUi.SideMargin, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { displaySeg(); ViewerHeaderControls() }
            }
        }
    }
}


/**
 * 映像と操作パネルの境界の持ち手。長押ししてからドラッグ(スワイプ)すると、領域の割合が変わる。
 * horizontalBar=true は、横に長い棒(上下に動かす)。false は縦に長い棒(左右に動かす)。
 */
@Composable
private fun AreaHandle(horizontalBar: Boolean, onDrag: (Float) -> Unit) {
    var active by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val len by androidx.compose.animation.core.animateDpAsState(if (active) 72.dp else 40.dp, label = "areaBar")
    Box(
        (if (horizontalBar) Modifier.fillMaxWidth().height(12.dp) else Modifier.fillMaxHeight().width(12.dp))
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { active = true; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                    onDrag = { change, d -> change.consume(); onDrag(if (horizontalBar) d.y else d.x) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 棒は、長押ししてドラッグしている間だけ見せる(普段は何も表示しない)
        if (active) Box(
            (if (horizontalBar) Modifier.width(len).height(4.dp) else Modifier.height(len).width(4.dp))
                .clip(CircleShape).background(primary),
        )
    }
}

/** メニューの、カメラ(スマホのカメラを PC のカメラにする)。PC のホストで、カメラを有効にしてあるときに使える。 */
@Composable
private fun CameraMenuItems(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val primary = MaterialTheme.colorScheme.primary
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) CameraSender.start(ctx, owner)
    }
    // 状態: 送信中 / 許可なし / 接続待ち / 映像待ち
    val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(CameraSender.running) { while (CameraSender.running) { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val camStatus = when {
        !hasPermission -> "カメラの許可がありません(タップで許可)"
        !CameraSender.running -> ""
        Stream.nativeInput == null -> "接続待ち(PC につながっていません)"
        CameraSender.lastSentAt > 0 && nowMs - CameraSender.lastSentAt < 3000 -> "送信中"
        else -> "カメラの映像を待っています"
    }
    DropdownMenuItem(
        text = {
            Column {
                Text(if (CameraSender.running) "カメラを PC に送っています" else "カメラを PC に送る")
                if (camStatus.isNotEmpty()) Text(camStatus, fontSize = 11.sp, color = if (hasPermission) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        },
        trailingIcon = { if (CameraSender.running) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Check, null, tint = primary) },
        onClick = {
            if (CameraSender.running) CameraSender.stop()
            else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) CameraSender.start(ctx, owner)
            else ask.launch(android.Manifest.permission.CAMERA)
            onDone()
        },
    )
    // 送っている映像の確認用の縮小表示(メニューを開いている間だけ作る)
    androidx.compose.runtime.DisposableEffect(Unit) { CameraSender.previewWanted = true; onDispose { CameraSender.previewWanted = false } }
    val pv = CameraSender.preview
    if (CameraSender.running && pv != null) androidx.compose.foundation.Image(
        pv.asImageBitmap(), null,
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).height(110.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
    )
    if (CameraSender.running) DropdownMenuItem(
        text = { Text(if (CameraSender.front) "背面カメラにする" else "前面カメラにする") },
        onClick = { CameraSender.switchCamera(ctx) },
    )
}
