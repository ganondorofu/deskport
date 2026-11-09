package io.github.ganondorofu.deskport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * キーボードとトラックパッドを1つにした表示(実験)。パネル全体がキーボードで、そのまま、カーソルの操作もできる。
 *
 *  - キーを押す(指をほぼ動かさずに離す): そのキーを入力する。離した瞬間に入力する(スワイプかどうかが、動かすまで分からないため)。
 *  - キーの上でスワイプ: キーは入力せず、トラックパッドになる(カーソルを相対移動。速度・加速・慣性の設定が効く)。
 *  - 2本指で上下: スクロール。2本指でタップ: 右クリック。
 *  - 上の帯: 左半分をタップ=左クリック、右半分をタップ=右クリック。ここでスワイプすると、カーソルが動き、長押ししてから動かすとドラッグ。
 *  - ⌫・矢印・Space・Enter・Tab・Del は、押し続けると連続入力(0.4秒後から)。修飾キー(Ctrl など)は、タップで押したままにして、次のキーと一緒に送る。
 */
@Composable
fun HybridKeyboard(sent: MutableList<String>, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latch = remember { ModLatch() }
    var pressedId by remember { mutableStateOf<Int?>(null) }
    // トラックパッドとして使っている間(スワイプ、2本指)は、キーボードを薄くして、トラックパッドらしい見た目にする
    var padActive by remember { mutableStateOf(false) }
    val kbAlpha by androidx.compose.animation.core.animateFloatAsState(if (padActive) 0.16f else 1f, androidx.compose.animation.core.tween(160), label = "hybridKb")
    val padAlpha by androidx.compose.animation.core.animateFloatAsState(if (padActive) 1f else 0f, androidx.compose.animation.core.tween(160), label = "hybridPad")
    val bounds = remember { HashMap<Int, Rect>() }       // キーの位置(画面上)
    val defs = remember { HashMap<Int, KeyDef>() }
    var origin by remember { mutableStateOf(Offset.Zero) }  // キーの領域の左上(画面上)
    val rows = rowsFor(KbSettings.layout, KbSettings.size, KbSettings.showFnRow, KbSettings.showNumRow)

    fun tap(cmd: String) {
        if (cmd in Modifiers) { latch.tap(cmd); return }
        val combo = (latch.consume() + cmd).joinToString("+")
        RemoteInput.combo(combo)
    }

    FocusBlock(modifier, color = PadColors.panel()) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (KbSettings.showShortcuts) ShortcutRow()
            // トラックパッドの帯: 左=左クリック、右=右クリック。スワイプで移動、長押ししてからドラッグ
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(OneUi.RadiusSmall))
                    .background(PadColors.fnKey().copy(alpha = 0.55f))
                    .pointerInput(Unit) { padGestures({ haptic.performHapticFeedback(HapticFeedbackType.LongPress) }, zones = true) },
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("左クリック", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box(Modifier.size(1.dp, 20.dp).align(Alignment.CenterVertically).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("右クリック", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            // キー(全面。タップ=入力、スワイプ=カーソル、2本指=スクロール)
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop * 1.6f
                        fun hit(p: Offset): Int? {
                            val r = p + origin
                            return bounds.entries.firstOrNull { it.value.inflate(3f).contains(r) }?.key
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val id = hit(down.position)
                            val k = id?.let { defs[it] }
                            pressedId = id
                            // 0=まだ決まらない(キー) 1=スワイプ(カーソル移動) 2=2本指(スクロール) 3=連続入力中
                            var mode = 0
                            var travel = 0f
                            var twoTravel = 0f
                            val t0 = down.uptimeMillis
                            var tEnd = t0
                            var repeatJob: Job? = null
                            val g3 = GestureTracker()
                            var three = false
                            CursorInertia.down()
                            while (true) {
                                val waitRepeat = mode == 0 && k != null && k.cmd in Repeating
                                val ev = if (waitRepeat) {
                                    withTimeoutOrNull((400L - (android.os.SystemClock.uptimeMillis() - t0)).coerceAtLeast(1L)) { awaitPointerEvent() }
                                } else awaitPointerEvent()
                                if (ev == null) {
                                    // 押し続けている: 連続入力
                                    mode = 3
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    tap(k!!.cmd)
                                    repeatJob = scope.launch { while (true) { delay(40); tap(k.cmd) } }
                                    continue
                                }
                                val pressed = ev.changes.filter { it.pressed }
                                tEnd = ev.changes.maxOf { it.uptimeMillis }
                                if (pressed.size >= 3) {
                                    if (mode != 2) { mode = 2; pressedId = null; padActive = true; repeatJob?.cancel(); CursorInertia.down() }
                                    three = true
                                    g3.feed(ev)
                                } else if (pressed.size >= 2) {
                                    if (mode != 2) { mode = 2; pressedId = null; padActive = true; repeatJob?.cancel(); CursorInertia.down() }
                                    val pan = ev.calculatePan()
                                    twoTravel += pan.getDistance()
                                    RemoteInput.scroll(pan.y * PointerSettings.scrollSpeed * (if (PointerSettings.naturalPad) 1f else -1f))
                                } else if (pressed.size == 1) {
                                    val d = pressed[0].positionChange()
                                    if (mode == 0) {
                                        travel += d.getDistance()
                                        if (travel > slop) { mode = 1; pressedId = null; padActive = true }   // 動かした: キーではなく、トラックパッド
                                    } else if (mode == 1) {
                                        val dd = padDelta(d)
                                        RemoteInput.move(dd)
                                        CursorInertia.track(dd, tEnd)
                                    }
                                }
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                            repeatJob?.cancel()
                            pressedId = null
                            padActive = false
                            if (three) g3.finish()
                            when (mode) {
                                0 -> if (k != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    // 修飾キーの長押しは、ラッチせずに1回だけ送る
                                    if (tEnd - t0 >= 450 && k.cmd in Modifiers) { RemoteInput.combo(k.cmd) } else tap(k.cmd)
                                }
                                1 -> CursorInertia.release(tEnd)
                                2 -> if (!three && twoTravel < 20f && tEnd - t0 < 300) RemoteInput.click(RemoteInput.RIGHT)
                            }
                        }
                    },
            ) {
                Column(Modifier.fillMaxSize().graphicsLayer { alpha = kbAlpha }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    rows.forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEachIndexed { c, k ->
                                if (k.spacer) { Box(Modifier.weight(k.weight)); return@forEachIndexed }
                                val id = r * 100 + c
                                defs[id] = k
                                val shown = when {
                                    k.label.length == 1 && k.label[0].isLetter() && latch.held("Shift") -> k.label.uppercase()
                                    k.label.length == 1 && latch.held("Shift") && KbSettings.layout == KbLayout.US -> ShiftedUs[k.label[0]] ?: k.label
                                    else -> k.label
                                }
                                KeyFace(
                                    k.label, shown, k.icon, k.isFn, k.accent, latch.held(k.label), pressedId == id,
                                    Modifier.weight(k.weight).onGloballyPositioned { bounds[id] = it.boundsInRoot() },
                                )
                            }
                        }
                    }
                }
                // トラックパッドとして使っている間の見た目(キーボードは薄くなる)
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = padAlpha }
                        .clip(RoundedCornerShape(OneUi.RadiusMedium)).background(PadColors.fnKey().copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("トラックパッド", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("指を離すと、キーボードに戻ります", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 見た目だけのキー(操作は、領域全体で受ける)。 */
@Composable
private fun KeyFace(
    label: String, shown: String, icon: ImageVector?, fn: Boolean, accent: Boolean, latched: Boolean, pressed: Boolean, modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = when { latched -> primary; accent -> primary; fn -> PadColors.fnKey(); else -> PadColors.key() }
    val fg = if (latched || accent) Color(0xFFFAFAFA) else MaterialTheme.colorScheme.onSurface
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.94f else 1f, label = "hybridKey")
    Box(
        modifier.fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp)).background(bg).background(if (pressed) fg.copy(alpha = 0.22f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = fg, modifier = Modifier.size(20.dp))
        else Text(
            if (label == "Space") "" else shown, color = fg, maxLines = 1,
            fontSize = if (fn) 12.sp else 16.sp, fontWeight = if (latched) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
