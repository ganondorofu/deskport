package io.github.ganondorofu.deskport

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * キーボードとトラックパッドを並べる。縦=上下に積む / 横=左右に並べる。
 *  - 間の持ち手をタップすると、その場にメニューが出る。アイコンで「入れ替え」「割合を戻す」を選ぶ(誤って押しにくい)。
 *  - 間の持ち手を長押ししてから動かす(スワイプ)と、大きさの割合を変えられる。割合は保存される。
 *  - 入れ替えは、2つが滑って位置を交代するアニメーションで行う。
 */
@Composable
fun Controls(
    kb: Boolean, pad: Boolean, vertical: Boolean, sent: MutableList<String>, modifier: Modifier,

) {
    if (LayoutState.hybridVisible) { Box(modifier) { HybridKeyboard(sent, Modifier.fillMaxSize()) }; return }
    if (!kb && !pad) return
    val keyboard: @Composable () -> Unit = { KeyboardPanel(sent, Modifier.fillMaxSize()) }
    val trackpad: @Composable () -> Unit = { Trackpad(Modifier.fillMaxSize()) }
    if (!(kb && pad)) {
        Box(modifier) { if (kb) keyboard() else trackpad() }
        return
    }
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    // 0=キーボードが先頭(左/上)、1=トラックパッドが先頭。値を滑らかに動かして、位置の交代をアニメーションにする
    val progress by androidx.compose.animation.core.animateFloatAsState(
        if (KbSettings.padFirst) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 320f), label = "swap",
    )
    val availPx = remember { FloatArray(1) { 1f } }
    val minPanelPx = remember { FloatArray(1) { 1f } }
    val handleDp = 28.dp      // つかめる範囲(隙間より広く取り、パネルの縁に少し重ねる)
    val gapDp = 12.dp         // 実際の隙間(これまでと同じ)。棒は見せないので、他の要素を圧迫しない
    androidx.compose.ui.layout.Layout(
        content = {
            keyboard()
            trackpad()
            SplitHandle(
                vertical, menuOpen,
                onOpen = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); menuOpen = true },
                onDismiss = { menuOpen = false },
                onSwap = { KbSettings.padFirst = !KbSettings.padFirst },
                onReset = { if (vertical) KbSettings.splitV = 0.62f else KbSettings.splitH = 0.64f },
                onDrag = { d ->
                    // 先頭に置いているものが、動かした向きに伸びる
                    val delta = d / availPx[0]
                    val kbDelta = if (KbSettings.padFirst) -delta else delta
                    // どちらのパネルも、狭くしすぎない(160dp より小さいと、キーや表示が崩れる)
                    val lo = (minPanelPx[0] / availPx[0]).coerceIn(0.05f, 0.45f)
                    if (vertical) KbSettings.splitV = (KbSettings.splitV + kbDelta).coerceIn(lo, 1f - lo)
                    else KbSettings.splitH = (KbSettings.splitH + kbDelta).coerceIn(lo, 1f - lo)
                },
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val handlePx = handleDp.roundToPx()
        val gapPx = gapDp.roundToPx()
        val avail = (if (vertical) h else w) - gapPx
        availPx[0] = avail.toFloat().coerceAtLeast(1f)
        minPanelPx[0] = 160.dp.toPx()
        val lo = (minPanelPx[0] / availPx[0]).coerceIn(0.05f, 0.45f)
        val share = (if (vertical) KbSettings.splitV else KbSettings.splitH).coerceIn(lo, 1f - lo)
        val kbSize = (avail * share).toInt(); val padSize = avail - kbSize
        fun fixed(size: Int) = if (vertical) androidx.compose.ui.unit.Constraints.fixed(w, size) else androidx.compose.ui.unit.Constraints.fixed(size, h)
        val kbP = measurables[0].measure(fixed(kbSize))
        val padP = measurables[1].measure(fixed(padSize))
        val hP = measurables[2].measure(fixed(handlePx))
        layout(w, h) {
            val kbPos = androidx.compose.ui.util.lerp(0f, (padSize + gapPx).toFloat(), progress).toInt()
            val padPos = androidx.compose.ui.util.lerp((kbSize + gapPx).toFloat(), 0f, progress).toInt()
            val hPos = androidx.compose.ui.util.lerp(kbSize.toFloat(), padSize.toFloat(), progress).toInt() - (handlePx - gapPx) / 2
            if (vertical) { kbP.placeRelative(0, kbPos); padP.placeRelative(0, padPos, 1f); hP.placeRelative(0, hPos, 2f) }
            else { kbP.placeRelative(kbPos, 0); padP.placeRelative(padPos, 0, 1f); hP.placeRelative(hPos, 0, 2f) }
        }
    }
}

/**
 * 2つのパネルの間の持ち手。タップでアイコンのメニューを出し、長押ししてからドラッグで大きさを変える。
 */
@Composable
private fun SplitHandle(
    vertical: Boolean, menuOpen: Boolean,
    onOpen: () -> Unit, onDismiss: () -> Unit, onSwap: () -> Unit, onReset: () -> Unit, onDrag: (Float) -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val barLen by androidx.compose.animation.core.animateDpAsState(if (active) 64.dp else 36.dp, label = "bar")
    Box(
        Modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { onOpen() }) }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { active = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                    onDrag = { change, d -> change.consume(); onDrag(if (vertical) d.y else d.x) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (active || menuOpen) Box(
            (if (vertical) Modifier.width(barLen).height(4.dp) else Modifier.height(barLen).width(4.dp))
                .clip(RoundedCornerShape(2.dp)).background(primary),
        )
        if (menuOpen) androidx.compose.ui.window.Popup(
            alignment = Alignment.Center, onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }
            androidx.compose.animation.AnimatedVisibility(
                shown,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
            ) {
                Row(
                    Modifier.shadow(10.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    HandleMenuIcon(if (vertical) Icons.Outlined.SwapVert else Icons.Outlined.SwapHoriz, "入れ替え") { onSwap(); onDismiss() }
                    HandleMenuIcon(Icons.Outlined.RestartAlt, "割合を戻す") { onReset(); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun HandleMenuIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface) }
}

/**
 * トラックパッド(相対操作)。
 *  - 1本指ドラッグ: カーソルを相対移動(移動量 × 速度 × 加速)
 *  - タップ: 左クリック / 2本指タップ: 右クリック / ダブルタップ: ダブルクリック
 *  - 2本指ドラッグ: スクロール
 *  - 指を置いて0.4秒動かさない、または、タップの直後にもう一度置いて動かす: ドラッグ(ボタンを押したまま移動)
 *  - 下の「左」「右」ボタンでもクリックできる
 * 速度・加速・スクロール速度は設定で変えられる。
 */
@Composable
fun Trackpad(modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    FocusBlock(modifier, color = PadColors.panel()) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(OneUi.RadiusMedium))
                        .background(PadColors.fnKey().copy(alpha = 0.55f)).pointerInput(Unit) { padGestures(onHold = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("トラックパッド", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "タップ=左クリック · 2本指タップ=右クリック · 長押ししてドラッグ=ドラッグ&ドロップ",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (PointerSettings.showWheel) ScrollWheel(Modifier.width(52.dp).fillMaxHeight())
            }
            if (PointerSettings.showButtons) Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PadButton("左", Modifier.weight(1f)) { RemoteInput.click(RemoteInput.LEFT) }
                PadButton("右", Modifier.weight(1f)) { RemoteInput.click(RemoteInput.RIGHT) }
            }
        }
    }
}

/**
 * スクロールホイール(Samsung の Flex モードのタッチパッドにあるものに近い形)。
 * 縦に指を滑らせると、目盛りが回って、リモートがスクロールする。目盛りをまたぐたびに、軽く振動する。
 * 速く動かして離すと、勢いが残って、少し回り続ける。
 */
@Composable
fun ScrollWheel(modifier: Modifier) {
    var phase by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var fling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val spacing = with(LocalDensity.current) { 14.dp.toPx() }
    val tickColor = MaterialTheme.colorScheme.onSurface
    fun move(dy: Float) {
        val before = kotlin.math.floor(phase / spacing)
        phase += dy
        if (kotlin.math.floor(phase / spacing) != before) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // タッチパッドの2本指スクロールと同じ向き
        RemoteInput.scroll(dy * PointerSettings.scrollSpeed * (if (PointerSettings.naturalWheel) 1f else -1f))
    }
    Box(
        modifier.clip(RoundedCornerShape(26.dp)).background(PadColors.fnKey().copy(alpha = 0.55f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    do {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.first()
                        if (c.pressed) {
                            vt.addPosition(c.uptimeMillis, c.position)
                            val dy = c.positionChange().y
                            if (dy != 0f) move(dy)
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    val v = vt.calculateVelocity().y
                    if (kotlin.math.abs(v) > 400f) {
                        fling = scope.launch {
                            var last = 0f
                            androidx.compose.animation.core.Animatable(0f).animateDecay(v, androidx.compose.animation.core.exponentialDecay(frictionMultiplier = 1.6f)) {
                                move(value - last); last = value
                            }
                        }
                    }
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val h = size.height; val w = size.width
            val off = ((phase % spacing) + spacing) % spacing
            var y = off - spacing
            while (y < h + spacing) {
                // 円柱のように、中央ほど長く濃く、上下の端ほど短く薄くする
                val t = ((y - h / 2) / (h / 2)).coerceIn(-1f, 1f)
                val k = 1f - t * t
                val len = w * (0.25f + 0.5f * k)
                drawLine(tickColor.copy(alpha = 0.12f + 0.45f * k), androidx.compose.ui.geometry.Offset((w - len) / 2, y), androidx.compose.ui.geometry.Offset((w + len) / 2, y), strokeWidth = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                y += spacing
            }
        }
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(OneUi.RadiusSmall)).background(PadColors.key()).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp) }
}

internal suspend fun PointerInputScope.padGestures(onHold: () -> Unit, zones: Boolean = false) {
    var lastTap = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val t0 = down.uptimeMillis
        val g3 = GestureTracker()   // 3本指のジェスチャー
        CursorInertia.down()
        var travel = 0f
        var maxFingers = 1
        var tEnd = t0
        // ドラッグ(ボタンを押したまま動かす)。次のどちらかで始まる: 指を置いて動かさずに0.4秒待つ / 軽くタップした直後に、もう一度置く
        var dragging = false
        if (t0 - lastTap < 300) { dragging = true; RemoteCursor.dragHide = true; RemoteInput.buttonDown(RemoteInput.LEFT) }
        while (true) {
            val ev = if (!dragging && maxFingers == 1 && travel < 30f) withTimeoutOrNull((400L - (android.os.SystemClock.uptimeMillis() - t0)).coerceAtLeast(1L)) { awaitPointerEvent() } else awaitPointerEvent()
            if (ev == null) { dragging = true; RemoteCursor.dragHide = true; RemoteInput.buttonDown(RemoteInput.LEFT); onHold(); continue }
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.size > maxFingers) maxFingers = pressed.size
            tEnd = ev.changes.maxOf { it.uptimeMillis }
            if (pressed.size >= 3) {
                CursorInertia.down()
                g3.feed(ev)
            } else if (pressed.size >= 2) {
                CursorInertia.down()
                val p = ev.calculatePan()
                travel += p.getDistance()
                val sign = if (PointerSettings.naturalPad) 1f else -1f
                RemoteInput.scroll(p.y * PointerSettings.scrollSpeed * sign)
            } else if (pressed.size == 1) {
                val d = pressed[0].positionChange()
                travel += d.getDistance()
                val dd = padDelta(d)
                RemoteInput.move(dd)
                if (!dragging) CursorInertia.track(dd, tEnd)
            }
            ev.changes.forEach { it.consume() }
            if (ev.changes.none { it.pressed }) break
        }
        g3.finish()   // 3本指のジェスチャーを実行(なければ、何もしない)
        if (dragging) { RemoteInput.buttonUp(RemoteInput.LEFT); RemoteCursor.dragHide = false; lastTap = 0L; return@awaitEachGesture }
        if (maxFingers == 1) CursorInertia.release(tEnd)   // 指を速く動かして離したら、慣性で少し滑らせる(設定でオン/オフ)

        if (travel < 20f && tEnd - t0 < 300) {
            when (maxFingers) {
                // zones: 右半分のタップは右クリック(統合キーボードの、トラックパッドの帯)
                1 -> { RemoteInput.click(if (zones && down.position.x > size.width / 2f) RemoteInput.RIGHT else RemoteInput.LEFT); lastTap = tEnd }
                2 -> RemoteInput.click(RemoteInput.RIGHT)
            }
        }
    }
}

internal data class KeyDef(val label: String, val weight: Float = 1f, val icon: ImageVector? = null, val accent: Boolean = false, val cmd: String = label) {
    val spacer get() = label.isEmpty()
    // Shift/Ctrl/⌫ など、文字以外のキー(Spaceは文字キーと同じ白)
    val isFn get() = (label.length > 1 && label != "Space") || icon != null || label in setOf("←", "↑", "↓", "→")
}

internal val Modifiers = listOf("Ctrl", "Win", "Alt", "Shift")

/** 長押しで連続入力するキー(誤って文字が連続しないよう、文字キーは含めない)。 */
internal val Repeating = setOf("⌫", "←", "↑", "↓", "→", "Space", "Enter", "Tab", "Del")

/** Shift を押したときの刻印(US配列)。 */
internal val ShiftedUs = mapOf(
    '1' to "!", '2' to "@", '3' to "#", '4' to "$", '5' to "%", '6' to "^", '7' to "&", '8' to "*", '9' to "(", '0' to ")",
    '`' to "~", '-' to "_", '=' to "+", '[' to "{", ']' to "}", '\\' to "|", ';' to ":", '\'' to "\"", ',' to "<", '.' to ">", '/' to "?",
)

private fun chars(s: String) = s.map { KeyDef(it.toString()) }

private fun gap(w: Float) = KeyDef("", w)
private fun fillTo(row: List<KeyDef>, total: Float): List<KeyDef> {
    val sum = row.sumOf { it.weight.toDouble() }.toFloat()
    return if (sum <= 0f) row else row.map { it.copy(weight = it.weight * total / sum) }
}

/** 実際のキーボードに近い配列。行の幅の合計がそろうように、キーの幅(重み)を決めている。 */
internal fun rowsFor(layout: KbLayout, size: KbSize, fn: Boolean, num: Boolean): List<List<KeyDef>> {
    val jis = layout == KbLayout.JIS
    val compact = size == KbSize.COMPACT
    val bs = KeyDef("⌫", 2f, Icons.AutoMirrored.Outlined.Backspace)
    // 実物のキーボードと同じ寸法(キー1つ=1)。どの行も、合計が15になるようにしてあるので、列が実物どおりに並ぶ。
    //  ANSI(US): Tab 1.5 / Caps 1.75 / 左Shift 2.25 / Enter 2.25 / 右Shift 2.75
    //  JIS: Tab 1.5 / Caps 1.75 / 左Shift 2.25 / 右Shift 1.75 / Enter は右上(縦長を、1行目に置く)
    val main = buildList<List<KeyDef>> {
        if (fn) add(buildList { add(KeyDef("Esc", 1.5f)); for (i in 1..12) add(KeyDef("F$i")); if (compact) add(KeyDef("Del", 1.5f)) })
        if (num) add(buildList {
            add(if (jis) KeyDef("半/全") else KeyDef("`"))
            addAll(chars("1234567890"))
            if (jis) { addAll(chars("-^")); add(KeyDef("¥")); add(KeyDef("⌫", 1f, Icons.AutoMirrored.Outlined.Backspace)) }
            else { addAll(chars("-=")); add(bs) }
        })
        add(buildList {
            add(KeyDef("Tab", 1.5f)); addAll(chars("qwertyuiop"))
            if (jis) { addAll(chars("@[")); add(KeyDef("Enter", 1.5f, Icons.Outlined.KeyboardReturn, accent = true)) }
            else { addAll(chars("[]")); add(KeyDef("\\", 1.5f)) }
            if (!num) { removeLast(); add(KeyDef("⌫", 1.5f, Icons.AutoMirrored.Outlined.Backspace)) }
        })
        add(buildList {
            add(KeyDef("Caps", 1.75f)); addAll(chars("asdfghjkl;"))
            if (jis) { addAll(chars(":]")); if (num) add(gap(1.25f)) else add(KeyDef("Enter", 1.25f, Icons.Outlined.KeyboardReturn, accent = true)) }
            else { addAll(chars("'")); add(KeyDef("Enter", 2.25f, Icons.Outlined.KeyboardReturn, accent = true)) }
        })
        add(buildList {
            add(KeyDef("Shift", 2.25f, Icons.Outlined.ArrowUpward)); addAll(chars("zxcvbnm,./"))
            if (jis) add(KeyDef("_"))
            add(KeyDef("Shift", if (jis) 1.75f else 2.75f, Icons.Outlined.ArrowUpward))
        })
        add(
            if (jis) buildList {
                add(KeyDef("Ctrl", 1.25f)); add(KeyDef("Win", 1.25f)); add(KeyDef("Alt", 1.25f)); add(KeyDef("無変換", 1.25f))
                add(KeyDef("Space", 3.25f)); add(KeyDef("変換", 1.25f)); add(KeyDef("かな", 1.25f)); add(KeyDef("Alt", 1.25f))
                if (compact) { add(KeyDef("←")); add(KeyDef("↓")); add(KeyDef("↑")); add(KeyDef("→")) } else add(KeyDef("Ctrl", 1.25f))
            } else buildList {
                add(KeyDef("Ctrl", 1.25f)); add(KeyDef("Win", 1.25f)); add(KeyDef("Alt", 1.25f)); add(KeyDef("Space", 6.25f)); add(KeyDef("Alt", 1.25f))
                if (compact) { add(KeyDef("Ctrl", 1.25f)); add(KeyDef("←")); add(KeyDef("↓")); add(KeyDef("↑")); add(KeyDef("→")) }
                else { add(KeyDef("Win", 1.25f)); add(KeyDef("Ctrl", 1.25f)) }
            },
        )
    }.map { row -> if (row.any { it.label == "Space" } || row.any { it.label == "Esc" }) fillTo(row, 15f) else row }
    if (compact) return main

    // 右側: 編集キー(Ins/Del/Home/End/PgUp/PgDn と矢印)。フルサイズでは、さらにテンキー
    val rowsIdx = buildList { if (fn) add("fn"); if (num) add("num"); add("tab"); add("caps"); add("shift"); add("bottom") }
    fun cluster(r: String): List<KeyDef> = when (r) {
        "fn" -> listOf(KeyDef("PrtSc"), KeyDef("ScrLk"), KeyDef("Pause"))
        "num" -> listOf(KeyDef("Ins"), KeyDef("Home"), KeyDef("PgUp"))
        "tab" -> listOf(KeyDef("Del"), KeyDef("End"), KeyDef("PgDn"))
        "shift" -> listOf(gap(1f), KeyDef("↑"), gap(1f))
        "bottom" -> listOf(KeyDef("←"), KeyDef("↓"), KeyDef("→"))
        else -> listOf(gap(1f), gap(1f), gap(1f))
    }
    fun pad(r: String): List<KeyDef> = when (r) {
        "num" -> listOf(KeyDef("NumLk"), KeyDef("/", cmd = "Num/"), KeyDef("*", cmd = "Num*"), KeyDef("-", cmd = "Num-"))
        "tab" -> listOf(KeyDef("7", cmd = "Num7"), KeyDef("8", cmd = "Num8"), KeyDef("9", cmd = "Num9"), KeyDef("+", cmd = "Num+"))
        "caps" -> listOf(KeyDef("4", cmd = "Num4"), KeyDef("5", cmd = "Num5"), KeyDef("6", cmd = "Num6"), gap(1f))
        "shift" -> listOf(KeyDef("1", cmd = "Num1"), KeyDef("2", cmd = "Num2"), KeyDef("3", cmd = "Num3"), KeyDef("Enter", 1f, Icons.Outlined.KeyboardReturn, accent = true))
        "bottom" -> listOf(KeyDef("0", 2f, cmd = "Num0"), KeyDef(".", cmd = "Num."), gap(1f))
        else -> listOf(gap(1f), gap(1f), gap(1f), gap(1f))
    }
    return main.mapIndexed { i, row ->
        val r = rowsIdx[i]
        row + gap(0.5f) + cluster(r) + if (size == KbSize.FULL) listOf(gap(0.5f)) + pad(r) else emptyList()
    }
}

/**
 * キーボード。入力方式(そのまま/まとめて/ライブ)と配列(US/JIS)は設定で変えられる。
 * 行は利用できる高さを等分して必ず全部表示する(スペースキーの行が隠れないよう、スクロールさせない)。
 *
 * Ctrl/Win/Alt/Shift:
 *  - タップ: 押したままにする(ラッチ)。次のキーを押すと、それと一緒に送って離す(ショートカット用)
 *  - 長押し: ラッチせずにそのキーを1回だけ送る(例: Win 単独でスタートメニュー)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardPanel(sent: MutableList<String>, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }   // 入力欄(変換中の範囲も持つ)
    val latch = remember { ModLatch() }
    val kh = KbSettings.heights[KbSettings.keyHeight]
    val haptic = LocalHapticFeedback.current

    fun tap(label: String) {
        if (label in Modifiers) { latch.tap(label); return }
        val combo = (latch.consume() + label).joinToString("+")
        RemoteInput.combo(combo)
    }
    fun longPress(label: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (label in Modifiers) RemoteInput.combo(label)   // 単独で1回だけ。ラッチ状態は変えない
    }

    FocusBlock(modifier, color = PadColors.panel()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Segmented(InputMethod.entries.map { it.label }, InputMethod.entries.indexOf(KbSettings.inputMethod)) {
                    KbSettings.inputMethod = InputMethod.entries[it]
                }
            }
            when (KbSettings.inputMethod) {
                InputMethod.DIRECT -> {
                    if (KbSettings.showShortcuts) ShortcutRow()
                    // 枠に収まらないときは、縦にも横にもスクロールできる(キーを小さくしすぎない)
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val rows = rowsFor(KbSettings.layout, KbSettings.size, KbSettings.showFnRow, KbSettings.showNumRow)
                        val gap = 5.dp
                        val fit = (maxHeight - gap * (rows.size - 1)) / rows.size
                        val rowH = fit.coerceAtLeast(36.dp).coerceAtMost(kh)
                        val units = rows.maxOf { rw -> rw.sumOf { it.weight.toDouble() } }.toFloat()
                        val contentW = maxWidth.coerceAtLeast((units * 18).dp)   // 狭い画面(ストレートバー)では、キーを小さくして収める。キー1つ分(重み1)が 18dp より狭くなるときだけ、横にスクロール
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).width(contentW),
                            verticalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            rows.forEach { row ->
                                Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    row.forEach { k ->
                                        if (k.spacer) { Box(Modifier.weight(k.weight)); return@forEach }
                                        val shown = when {
                                            k.label.length == 1 && k.label[0].isLetter() && latch.held("Shift") -> k.label.uppercase()
                                            k.label.length == 1 && latch.held("Shift") && KbSettings.layout == KbLayout.US -> ShiftedUs[k.label[0]] ?: k.label
                                            else -> k.label
                                        }
                                        KeyButton(
                                            k.label, shown, k.icon, k.isFn, k.accent, latch.held(k.label), k.cmd in Repeating, Modifier.weight(k.weight),
                                            { tap(k.cmd) }, { longPress(k.cmd) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // 端末のキーボード(IME)で入力する(日本語入力は、こちら)。
                    //  まとめて: 入力欄にためて、「送信」で、まとめて送る
                    //  ライブ: 確定した文字を、すぐ送って、入力欄から消す(変換中の文字だけが、入力欄に残る)
                    val realtime = KbSettings.inputMethod == InputMethod.REALTIME
                    // 入力方式を変えたら、入力欄を空にする
                    LaunchedEffect(KbSettings.inputMethod) { text = ""; tfv = androidx.compose.ui.text.input.TextFieldValue("") }
                    if (KbSettings.showShortcuts) ShortcutRow()
                    BasicTextField(
                        value = tfv,
                        onValueChange = { nv ->
                            tfv = nv; text = nv.text
                            if (realtime) {
                                // 変換中の文字(ひらがななど)は、送らない。確定した文字だけを送って、入力欄から消す(フリック入力も、同じ)
                                val comp = nv.composition
                                val committed = if (comp != null) nv.text.removeRange(comp.min, comp.max) else nv.text
                                if (committed.isNotEmpty()) {
                                    RemoteInput.text(committed)
                                    val rest = if (comp != null) nv.text.substring(comp.min, comp.max) else ""
                                    text = rest
                                    tfv = androidx.compose.ui.text.input.TextFieldValue(
                                        rest, androidx.compose.ui.text.TextRange(rest.length),
                                        if (rest.isEmpty()) null else androidx.compose.ui.text.TextRange(0, rest.length),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 96.dp).clip(RoundedCornerShape(OneUi.RadiusMedium))
                            .background(PadColors.key()).padding(12.dp)
                            .onPreviewKeyEvent { ev ->
                                // ライブ: 入力欄が空のときのバックスペースは、PC に送る(PC の文字を、1つ消す)
                                if (realtime && tfv.text.isEmpty() && ev.key == androidx.compose.ui.input.key.Key.Backspace && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { RemoteInput.combo("⌫"); true } else false
                            },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (tfv.text.isEmpty()) Text(
                                if (realtime) "確定した文字を、すぐ送信(変換が終わった分から送ります)" else "ここに入力してまとめて送信",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            inner()
                        },
                    )
                    // よく使うキー(入力欄では、打てないもの)
                    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Esc", "Tab", "⌫", "Enter", "←", "↓", "↑", "→").forEach { k ->
                            KeyButton(
                                k, k,
                                when (k) { "⌫" -> Icons.AutoMirrored.Outlined.Backspace; "Enter" -> Icons.Outlined.KeyboardReturn; else -> null },
                                true, k == "Enter", false, k in Repeating, Modifier.weight(1f), { RemoteInput.combo(k) }, {},
                            )
                        }
                    }
                    if (!realtime) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (sent.isNotEmpty()) "最後に送信: ${sent.last()}(タップで入力欄に戻す)" else "", fontSize = 12.sp, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).clickable(enabled = sent.isNotEmpty()) { text = sent.last(); tfv = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)) },
                        )
                        PillButton("送信", onClick = {
                            if (text.isNotEmpty()) { sent.add(text); RemoteInput.text(text); text = ""; tfv = androidx.compose.ui.text.input.TextFieldValue("") }
                        })
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 小さな切替(セグメント): 外枠の中で、選択中だけ白いキー色になる。 */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(PadColors.segment()).padding(2.dp)) {
        options.forEachIndexed { i, l ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) PadColors.key() else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ショートカットの行。タップで、その組み合わせを送る。 */
@Composable
internal fun ShortcutRow() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        KbSettings.shortcuts.forEach { s -> ShortcutChip(s) { RemoteInput.combo(s) } }
    }
}

/**
 * 修飾キー(Ctrl、Shift、Alt、Win)の状態。
 *  - 1回タップ: 次のキーと一緒に送って離す(ラッチ)。
 *  - 2回タップ(0.35秒以内。設定でオフにできる): 押したままにする(固定)。もう一度タップすると離す。Alt を固定して Tab を何度か送る、などに使う。
 */
internal class ModLatch {
    var oneShot by mutableStateOf(setOf<String>())
        private set
    private var lastTap: String? = null
    private var lastAt = 0L

    /** 押された状態(次のキーに付く、または固定中)か。 */
    fun held(name: String) = name in oneShot || name in RemoteInput.heldMods

    fun tap(name: String) {
        val now = System.currentTimeMillis()
        when {
            name in RemoteInput.heldMods -> { RemoteInput.releaseMod(name); lastTap = null }
            KbSettings.holdOnDoubleTap && name == lastTap && now - lastAt < 350 -> {
                oneShot = oneShot - name
                RemoteInput.holdMod(name)
                lastTap = null
            }
            else -> { oneShot = if (name in oneShot) oneShot - name else oneShot + name; lastTap = name; lastAt = now }
        }
    }

    /** 次のキーに付ける、1回だけの修飾キーを取り出す(取り出すと、空になる)。固定中のものは、送るときに自動で付いている。 */
    fun consume(): List<String> {
        val m = Modifiers.filter { it in oneShot }
        oneShot = emptySet()
        return m
    }
}

@Composable
private fun ShortcutChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(RoundedCornerShape(8.dp)).background(PadColors.fnKey())
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface) }
}

/**
 * 1つのキー。文字キーは白、操作キー(Shift/Ctrl/Enter 等)は暗めの色、Enter は青。
 * ラッチ中のキーは青く塗る。
 *  - 連続入力するキー(⌫、矢印、Space、Enter、Tab、Del)は、押し続けると0.4秒後から連続で入力する。
 *  - それ以外は、タップで入力。0.45秒以上の長押しは onLong(修飾キーを、ラッチせずに1回だけ送る)。
 */
@Composable
private fun KeyButton(
    label: String, shown: String, icon: ImageVector?, fn: Boolean, accent: Boolean, latched: Boolean, repeat: Boolean,
    modifier: Modifier, onTap: () -> Unit, onLong: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = when {
        latched -> primary
        accent -> primary
        fn -> PadColors.fnKey()
        else -> PadColors.key()
    }
    val fg = if (latched || accent) Color(0xFFFAFAFA) else MaterialTheme.colorScheme.onSurface
    // 打鍵の手応え: 押している間は色を重ねて少し縮め、ホバー(マウス/ペン)でも薄く反応し、押した瞬間に控えめに振動する
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    val hovered by src.collectIsHoveredAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val tapNow by androidx.compose.runtime.rememberUpdatedState(onTap)
    val longNow by androidx.compose.runtime.rememberUpdatedState(onLong)
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.94f else 1f, label = "keyScale")
    val overlay = when { pressed -> fg.copy(alpha = 0.22f); hovered -> fg.copy(alpha = 0.08f); else -> Color.Transparent }
    Box(
        modifier.fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp)).background(bg).background(overlay)
            .hoverable(src)
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (repeat) {
                        tapNow()
                        val job = scope.launch { delay(400); while (true) { tapNow(); delay(35) } }
                        try { tryAwaitRelease() } finally { job.cancel(); pressed = false }
                    } else {
                        val t0 = System.currentTimeMillis()
                        val released = try { tryAwaitRelease() } finally { pressed = false }
                        if (released) { if (System.currentTimeMillis() - t0 >= 450) longNow() else tapNow() }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = fg, modifier = Modifier.size(20.dp))
        else Text(
            if (label == "Space") "" else shown, color = fg, maxLines = 1,
            fontSize = if (fn) 12.sp else 16.sp,
            fontWeight = if (latched) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
