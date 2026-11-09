package io.github.ganondorofu.deskport

import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * 映像表示。配信は常に1本の固定キャンバス、表示する範囲をクライアント側でクロップ+ズーム。
 * selected=null なら全体(全ディスプレイ)、それ以外はそのディスプレイだけ。切替はアニメーションのみで通信は発生しない。
 *
 * 1本指の意味は ViewerSettings.touchMode で変わる。2本指は、ビューではズーム/パン、タップと画面パッドでは、ピンチでズーム、上下に動かすとスクロール。
 *  - ビュー: 1本指でパン
 *  - タップ: 触った位置にカーソルを置く(絶対座標)。タップ=左クリック、2本指タップ=右クリック
 *  - 画面パッド: 画面全体をトラックパッドとして相対移動
 * マウス(USB/BLE)はOSがポインタイベントとして渡す。タッチと区別し、位置をそのままリモートのカーソルへ反映する。
 */
@Composable
fun RemoteViewport(modifier: Modifier, selected: Int?) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    // 現在の変換(scale, translationX, translationY)。ポインタ処理から画面座標→キャンバス座標へ変換するのに使う
    val tf = remember { floatArrayOf(1f, 0f, 0f) }
    // アニメーションは、ディスプレイ切替・ズーム戻し・ダブルタップのときだけ。指で動かしている間や、カーソル追従の間は、
    // バネを通さず位置を直接決める(バネを通すと、映像が遅れて動き、即座に動くカーソルとずれて、揺れて見える)
    var animating by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }   // 指が触れている間は、端を越えて動かせる(離すと、元に戻る)
    LaunchedEffect(animating) { if (animating) { kotlinx.coroutines.delay(450); animating = false } }
    LaunchedEffect(selected) { zoom = 1f; panX = 0f; panY = 0f; animating = true }
    LaunchedEffect(ViewerSettings.resetTick) { zoom = 1f; panX = 0f; panY = 0f; animating = true }
    // ジェスチャーなどからの、ズームの指示(拡大・縮小・戻す)
    var seenZoomTick by remember { androidx.compose.runtime.mutableIntStateOf(ZoomCommand.tick) }
    LaunchedEffect(ZoomCommand.tick) {
        if (ZoomCommand.tick == seenZoomTick) return@LaunchedEffect
        seenZoomTick = ZoomCommand.tick
        if (ZoomCommand.factor == 0f) { zoom = 1f; panX = 0f; panY = 0f } else if (!ViewerSettings.locked) zoom = (zoom * ZoomCommand.factor).coerceIn(1f, 6f)
        animating = true
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 指を離したあとの慣性(ビューで1本指で動かしたとき)。指を置いたら止める
    val flingScope = androidx.compose.runtime.rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // ピンチ: 指の中点の下にある内容が動かないように拡大・縮小する(中点を中心に拡大し、指の移動ぶんも加える)
    fun pinch(z: Float, pan: Offset, c: Offset, vw: Float, vh: Float) {
        val old = zoom
        val nz = (old * z).coerceIn(1f, 6f)
        val r = nz / old
        val ux = c.x - vw / 2f; val uy = c.y - vh / 2f
        panX = ux + (panX - ux) * r + pan.x
        panY = uy + (panY - uy) * r + pan.y
        zoom = nz
    }
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(OneUi.RadiusBlock)).clipToBounds()
            .background(Color.Black)
            .pointerInput(Unit) {
                var active = false
                var downAt = 0L
                var travel = 0f
                var maxFingers = 0
                var lastTapUp = 0L
                val g3 = GestureTracker()   // 3本指のジェスチャー
                var dragging = false   // 長押しで始まったドラッグ(左ボタンを押したまま)
                // 2本指の動きの種類(タップ・画面パッドのとき): 0=まだ決まらない 1=ピンチ(ズーム) 2=上下に動かす(スクロール)
                var twoKind = 0
                var accZoom = 1f
                var accX = 0f
                var accY = 0f
                var vx = 0f; var vy = 0f; var lastMoveAt = 0L   // 1本指でのパンの速度(慣性用)
                awaitPointerEventScope {
                    while (true) {
                        // 指を置いて、ほぼ動かさずに0.4秒たったら、ドラッグを始める(タップ・画面パッドのとき)
                        val candidate = active && maxFingers == 1 && !dragging && travel < 30f && ViewerSettings.touchMode != TouchMode.VIEW
                        val ev = if (candidate) {
                            withTimeoutOrNull((400L - (android.os.SystemClock.uptimeMillis() - downAt)).coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Initial) }
                        } else awaitPointerEvent(PointerEventPass.Initial)
                        if (ev == null) {
                            dragging = true; RemoteCursor.dragHide = true; RemoteInput.buttonDown(RemoteInput.LEFT)
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            continue
                        }
                        val mouse = ev.changes.firstOrNull { it.type == PointerType.Mouse }
                        if (mouse != null) { handleMouse(ev, mouse, tf); continue }

                        val pressed = ev.changes.filter { it.pressed }
                        val mode = ViewerSettings.touchMode
                        if (!active && pressed.isNotEmpty()) {
                            active = true; touching = true; downAt = pressed[0].uptimeMillis; travel = 0f; maxFingers = 0; dragging = false
                            twoKind = 0; accZoom = 1f; accX = 0f; accY = 0f; vx = 0f; vy = 0f
                            flingJob?.cancel()
                            CursorInertia.down()
                            if (mode == TouchMode.TAP && pressed.size == 1) setCursorFromScreen(pressed[0].position, tf)
                        }
                        if (pressed.size > maxFingers) maxFingers = pressed.size

                        if (pressed.size >= 3) {
                            CursorInertia.down()
                            g3.feed(ev)
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size >= 2) {
                            CursorInertia.down()
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val cen = ev.calculateCentroid()
                            if (mode == TouchMode.VIEW) {
                                if (!ViewerSettings.locked) pinch(z, pan, cen, size.width.toFloat(), size.height.toFloat())
                            } else {
                                // タップ・画面パッド: ピンチならズーム、2本指を上下に動かすならリモートのスクロール(どちらかに決めたら、指を離すまで変えない)
                                if (twoKind == 0) {
                                    accZoom *= z; accX += pan.x; accY += pan.y
                                    if (kotlin.math.abs(kotlin.math.ln(accZoom)) > 0.08f) twoKind = 1
                                    else if (kotlin.math.hypot(accX, accY) > 16f) twoKind = 2
                                } else if (twoKind == 1) {
                                    if (!ViewerSettings.locked) pinch(z, pan, cen, size.width.toFloat(), size.height.toFloat())
                                } else {
                                    RemoteInput.scroll(pan.y * PointerSettings.scrollSpeed * (if (PointerSettings.naturalScreen) 1f else -1f))
                                }
                            }
                            travel += pan.getDistance()
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && pressed[0].previousPressed) {
                            val ch = pressed[0]
                            val d = ch.positionChange()
                            travel += d.getDistance()
                            when (mode) {
                                TouchMode.VIEW -> if (!ViewerSettings.locked) {
                                    panX += d.x; panY += d.y
                                    val dt = (ch.uptimeMillis - lastMoveAt).coerceIn(1L, 100L)
                                    vx = vx * 0.5f + (d.x / dt) * 0.5f; vy = vy * 0.5f + (d.y / dt) * 0.5f   // px/ms
                                    lastMoveAt = ch.uptimeMillis
                                }
                                TouchMode.TAP -> setCursorFromScreen(ch.position, tf)
                                TouchMode.SCREEN_PAD -> { val dd = padDelta(d); RemoteInput.move(dd); CursorInertia.track(dd, ch.uptimeMillis) }
                            }
                            ch.consume()
                        }

                        if (active && pressed.isEmpty()) {
                            active = false; touching = false
                            val upAt = ev.changes.maxOf { it.uptimeMillis }
                            if (maxFingers >= 3) g3.finish()   // 3本指のジェスチャーを実行
                            // ビューで1本指で動かしたあとに離したら、速度を落としながら滑らせる
                            if (mode == TouchMode.VIEW && maxFingers == 1 && !ViewerSettings.locked && upAt - lastMoveAt < 80 && kotlin.math.hypot(vx, vy) > 0.3f) {
                                var fx = vx; var fy = vy
                                flingJob = flingScope.launch {
                                    while (kotlin.math.hypot(fx, fy) > 0.02f) {
                                        panX += fx * 16f; panY += fy * 16f
                                        fx *= 0.93f; fy *= 0.93f
                                        kotlinx.coroutines.delay(16)
                                    }
                                }
                            }
                            if (dragging) { RemoteInput.buttonUp(RemoteInput.LEFT); RemoteCursor.dragHide = false; dragging = false } else {
                            if (mode == TouchMode.SCREEN_PAD && maxFingers == 1) CursorInertia.release(upAt)
                            if (travel < 24f && upAt - downAt < 300) {
                                when {
                                    mode == TouchMode.VIEW && maxFingers == 1 -> {
                                        // ビュー: ダブルタップでズームの切替
                                        if (upAt - lastTapUp < 300 && !ViewerSettings.locked) {
                                            zoom = if (zoom > 1f) 1f else 2.5f; panX = 0f; panY = 0f; animating = true
                                        }
                                        lastTapUp = upAt
                                    }
                                    maxFingers == 1 -> RemoteInput.click(RemoteInput.LEFT)
                                    maxFingers == 2 -> RemoteInput.click(RemoteInput.RIGHT)
                                }
                            }
                            }
                        }
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val vw = with(density) { maxWidth.toPx() }
        val vh = with(density) { maxHeight.toPx() }
        val cw = canvasW()
        val sel = selected?.takeIf { it in 0..3 }
        val rx = if (sel != null) sel * Stream.WIDTH.toFloat() else 0f; val ry = 0f
        val rw = if (sel != null) Stream.WIDTH.toFloat() else cw; val rh = CANVAS_H
        val base = min(vw / rw, vh / rh)

        // カーソル追従: ズーム中は、カーソルが画面中央に来るようにパンを決める(手動のパンは上書きされる)
        val follow = ViewerSettings.follow && zoom > 1.01f
        // 映像の外(黒い部分)が見えないように、動かせる範囲を制限する(映像のほうが画面より小さい向きは、中央に固定)。
        // 映像の中心は枠の中心と同じ位置にあるので、中心からのずれの最大値だけを求めればよい
        val vidW: Float; val vidH: Float
        if (sel != null) { val vr = Stream.videoRect(sel); vidW = vr[2]; vidH = vr[3] } else { vidW = rw; vidH = rh }
        val limX = maxOf(0f, (vidW * base * zoom - vw) / 2f)
        val limY = maxOf(0f, (vidH * base * zoom - vh) / 2f)
        // 指で動かしている間は、端を越えても、抵抗をつけて動かせる(ゴムのように)。離すと、範囲の中へ戻る
        fun rubber(v: Float, lim: Float): Float {
            if (v in -lim..lim) return v
            val ex = kotlin.math.abs(v) - lim
            val shown = lim + ex * 0.35f / (1f + ex / 600f)
            return if (v < 0) -shown else shown
        }
        val rawX = if (follow) (rx + rw / 2 - RemoteCursor.x) * base * zoom else panX
        val rawY = if (follow) (ry + rh / 2 - RemoteCursor.y) * base * zoom else panY
        val effPanX = if (touching && !follow) rubber(rawX, limX) else rawX.coerceIn(-limX, limX)
        val effPanY = if (touching && !follow) rubber(rawY, limY) else rawY.coerceIn(-limY, limY)
        // 指を離したら、保存している値も範囲に戻す(戻る動きはなめらかに)
        androidx.compose.runtime.SideEffect {
            if (!follow && !touching && (panX != effPanX || panY != effPanY)) { animating = true; panX = effPanX; panY = effPanY }
        }
        // 追従がオン・オフに変わった瞬間は、位置が飛ばないよう、なめらかに動かす
        LaunchedEffect(follow) { animating = true }

        val sTarget = base * zoom
        val txTarget = vw / 2 - (rx + rw / 2) * base * zoom + effPanX
        val tyTarget = vh / 2 - (ry + rh / 2) * base * zoom + effPanY
        val sAnim by animateFloatAsState(sTarget, label = "scale")
        val txAnim by animateFloatAsState(txTarget, label = "tx")
        val tyAnim by animateFloatAsState(tyTarget, label = "ty")
        val s = if (animating) sAnim else sTarget
        val tx = if (animating) txAnim else txTarget
        val ty = if (animating) tyAnim else tyTarget
        tf[0] = s; tf[1] = tx; tf[2] = ty

        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .size(with(density) { cw.toDp() }, with(density) { CANVAS_H.toDp() })
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = s; scaleY = s; translationX = tx; translationY = ty
                },
        ) {
            if (Stream.state != Stream.State.IDLE) {
                // 実際の配信: 各ディスプレイの映像を、キャンバス上のそのディスプレイの位置に置く(黒の上に重ねる)
                val slot = Modifier.size(with(density) { Stream.WIDTH.toFloat().toDp() }, with(density) { CANVAS_H.toDp() })
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (Stream.native) {
                        Stream.nativeDisplays.toList().forEach { k ->
                            androidx.compose.runtime.key(k) {
                                val vr = Stream.videoRect(k)
                                Box(
                                    Modifier.offset { IntOffset((k * Stream.WIDTH + vr[0]).toInt(), vr[1].toInt()) }
                                        .size(with(density) { vr[2].toDp() }, with(density) { vr[3].toDp() }),
                                ) {
                                    StreamSurface(Stream.decoders[k], Modifier.fillMaxSize())
                                }
                            }
                        }
                    } else if (!Stream.switching) {
                        Box(Modifier.offset { IntOffset((Stream.display * Stream.WIDTH).toInt(), 0) }.then(slot)) {
                            StreamSurface(Stream.decoder, Modifier.fillMaxSize())
                        }   // 自作ホスト以外の経路は残っていない(到達しない)
                    }
                }
            } else Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // 1枚だけ表示していて、カーソルが別のディスプレイにあるとき: その向きの画面の端に、矢印を出す(設定でオフにできる)
        val off = Stream.cursorOffscreen
        if (off != null && sel != null && PointerSettings.showOffscreenIndicator && Stream.state == Stream.State.STREAMING) {
            val (ox, oy) = off
            val len = kotlin.math.sqrt(ox * ox + oy * oy).coerceAtLeast(0.001f)
            val ux = ox / len; val uy = oy / len
            val margin = with(density) { 36.dp.toPx() }
            val hw = vw / 2f - margin; val hh = vh / 2f - margin
            // 中心から向きの先へ伸ばして、余白を残した枠にぶつかる点に置く
            val t = minOf(if (kotlin.math.abs(ux) > 1e-4f) hw / kotlin.math.abs(ux) else Float.MAX_VALUE, if (kotlin.math.abs(uy) > 1e-4f) hh / kotlin.math.abs(uy) else Float.MAX_VALUE)
            val px = vw / 2f + ux * t; val py = vh / 2f + uy * t
            val half = with(density) { 22.dp.toPx() }
            val angle = Math.toDegrees(kotlin.math.atan2(uy.toDouble(), ux.toDouble())).toFloat()
            Box(
                Modifier.offset { IntOffset((px - half).toInt(), (py - half).toInt()) }.size(44.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(22.dp).graphicsLayer { rotationZ = angle }) {
                    val p = Path().apply { moveTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height * 0.1f); lineTo(size.width * 0.35f, size.height / 2); lineTo(size.width * 0.15f, size.height * 0.9f); close() }
                    drawPath(p, Color.White)
                }
            }
        }

        // リモートのマウスカーソル(ズームしても大きさは一定)。自作ホストでは、ホストの本物の形(矢印、縦線、手、読み込み中など)を描く
        val cx = tx + RemoteCursor.x * s
        val cy = ty + RemoteCursor.y * s
        // 映像にはカーソルが写らないので、ホストが送る位置と形で描く(ずれて二重に見えるため)
        // ドラッグ中は出さない(ホストの映像にも、ドラッグ中のカーソルや移動中のものが写って、二重に見えるため)
        if ((Stream.state != Stream.State.STREAMING || Stream.native) && RemoteCursor.visible && !RemoteCursor.dragHide) {
            val shape = if (Stream.native) CursorShapes.current else null
            // ホストの1ピクセルを画面の何 dp にするか(設定の「カーソルの大きさ」に比例)
            val unit = (0.8f * PointerSettings.cursorScale).let { u -> if (shape != null && !shape.busy) maxOf(u, 12f / maxOf(shape.w, shape.h)) else u }
            val unitPx = unit * density.density
            if (shape != null && shape.busy) {
                // 読み込み中: 画像は1コマしか取れないので、回転する輪を描く
                val rot by androidx.compose.animation.core.rememberInfiniteTransition(label = "busy").animateFloat(
                    0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "rot",
                )
                val sizeDp = 32f * unit
                val half = with(density) { (sizeDp / 2).dp.toPx() }
                Canvas(Modifier.offset { IntOffset((cx - half).toInt(), (cy - half).toInt()) }.size(sizeDp.dp)) {
                    val stroke = size.width * 0.14f
                    val inset = stroke
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Color.Black, rot, 250f, false, Offset(inset, inset), arcSize, style = Stroke(stroke * 1.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawArc(Color.White, rot, 250f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            } else if (shape != null) {
                Image(
                    shape.image, null,
                    Modifier.offset { IntOffset((cx - shape.hotX * unitPx).toInt(), (cy - shape.hotY * unitPx).toInt()) }
                        .size((shape.w * unit).dp, (shape.h * unit).dp),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            } else {
                // 形をまだ受け取っていない間: 矢印
                Canvas(Modifier.offset { IntOffset(cx.toInt(), cy.toInt()) }.size((28f * PointerSettings.cursorScale).dp)) {
                    val k = size.width / 20f
                    val path = Path().apply {
                        moveTo(0f, 0f); lineTo(0f, 17f * k); lineTo(4.5f * k, 13f * k); lineTo(8f * k, 20f * k)
                        lineTo(11f * k, 18.5f * k); lineTo(7.5f * k, 12f * k); lineTo(13f * k, 12f * k); close()
                    }
                    drawPath(path, Color.White)
                    drawPath(path, Color.Black, style = Stroke(width = 1.6f * k))
                }
            }
        }

        // 接続中・失敗のメッセージだけを、映像の中央に出す(配信中の fps などはヘッダーに出すので、映像に被らない)
        StreamStatus(Modifier.align(Alignment.Center))
    }
}

/** 実際の映像の表示面。TextureView なので、拡大・移動の変換がそのまま効く。 */
@Composable
private fun StreamSurface(decoder: VideoDecoder, modifier: Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        decoder.attachSurface(android.view.Surface(st))
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        decoder.attachSurface(null)     // 画面が消えても復号は続ける
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                if (isAvailable) surfaceTexture?.let { decoder.attachSurface(android.view.Surface(it)) }
            }
        },
    )
}

/** 接続中/失敗のメッセージ(配信中は何も出さない。fps と形式はヘッダーに出す)。 */
@Composable
private fun StreamStatus(modifier: Modifier) {
    val text = when (Stream.state) {
        Stream.State.STARTING, Stream.State.FAILED -> Stream.message
        else -> ""
    }
    if (text.isNotEmpty()) Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = modifier.clip(CircleShape).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun setCursorFromScreen(p: Offset, tf: FloatArray) {
    RemoteInput.moveTo((p.x - tf[1]) / tf[0], (p.y - tf[2]) / tf[0])
}

private var mouseButtons = 0

/** OSが渡すマウス(USB/BLE)のイベント。位置は絶対座標で、ボタンの押下/離上とスクロールをホストへ送る。 */
private fun handleMouse(ev: PointerEvent, ch: PointerInputChange, tf: FloatArray) {
    if (ev.type == PointerEventType.Exit) return
    setCursorFromScreen(ch.position, tf)
    if (ev.type == PointerEventType.Scroll) {
        // ホイールは上回しがプラスの scrollDelta.y が負。ホイールの向きはホストにそのまま渡すため符号を反転
        RemoteInput.scroll(-ch.scrollDelta.y * 40f * PointerSettings.scrollSpeed * (if (PointerSettings.naturalScreen) -1 else 1))
    }
    val b = ev.buttons
    val now = (if (b.isPrimaryPressed) 1 else 0) or (if (b.isSecondaryPressed) 2 else 0) or (if (b.isTertiaryPressed) 4 else 0) or
        (if (b.isBackPressed) 8 else 0) or (if (b.isForwardPressed) 16 else 0)
    val map = intArrayOf(RemoteInput.LEFT, RemoteInput.RIGHT, RemoteInput.MIDDLE, RemoteInput.BACK, RemoteInput.FORWARD)
    for (i in 0 until 5) {
        val bit = 1 shl i
        val was = mouseButtons and bit != 0
        val isDown = now and bit != 0
        if (isDown && !was) RemoteInput.buttonDown(map[i]) else if (!isDown && was) RemoteInput.buttonUp(map[i])
    }
    mouseButtons = now
}

/** ヘッダーに置く、ビューワーの操作: 画面モード(ビュー/タップ/パッド)と、固定・カーソル追従・ズーム戻し。 */
@Composable
fun ViewerHeaderControls() {
    val primary = MaterialTheme.colorScheme.primary
    val plain = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TouchMode.entries.forEach { m ->
                val sel = ViewerSettings.touchMode == m
                Text(
                    m.label.removePrefix("画面"), fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) Color.White else plain, maxLines = 1,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (sel) primary else Color.Transparent)
                        .clickable { ViewerSettings.touchMode = m }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        ToolIcon(Icons.Outlined.FitScreen, false, primary, plain) { ViewerSettings.resetTick++ }
    }
}

/** メニューに入れる、ビューワーの設定: 固定、カーソル追従、カーソルの大きさ。 */
@Composable
fun ViewerMenuItems() {
    val primary = MaterialTheme.colorScheme.primary
    androidx.compose.material3.DropdownMenuItem(
        text = { Text("表示を固定(ズーム・移動をしない)") },
        leadingIcon = { Icon(if (ViewerSettings.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null) },
        trailingIcon = { if (ViewerSettings.locked) Icon(Icons.Outlined.Check, null, tint = primary) },
        onClick = { ViewerSettings.locked = !ViewerSettings.locked },
    )
    androidx.compose.material3.DropdownMenuItem(
        text = { Text("カーソル追従") },
        leadingIcon = { Icon(Icons.Outlined.MyLocation, null) },
        trailingIcon = { if (ViewerSettings.follow) Icon(Icons.Outlined.Check, null, tint = primary) },
        onClick = { ViewerSettings.follow = !ViewerSettings.follow },
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(if (StreamSettings.audio) "音量 ${(StreamSettings.volume * 100).toInt()}%" else "音量(音声はオフです)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.Slider(
            StreamSettings.volume, { StreamSettings.volume = it; AudioPlayer.volume = it },
            valueRange = 0f..1f, enabled = StreamSettings.audio, modifier = Modifier.width(200.dp),
        )
    }
}

@Composable
private fun ToolIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, primary: Color, plain: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (on) primary.copy(alpha = 0.16f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) primary else plain, modifier = Modifier.size(18.dp)) }
}
