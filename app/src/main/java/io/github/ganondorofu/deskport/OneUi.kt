package io.github.ganondorofu.deskport

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 操作パネル(キーボード/トラックパッド)の地色と、キーの色。ライトは薄いグレー地に白いキー、ダークは濃いグレー地に明るいキー。 */
object PadColors {
    @Composable fun panel() = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFE4E4E9)
    @Composable fun key() = if (isSystemInDarkTheme()) Color(0xFF3A3A3C) else Color(0xFFFFFFFF)
    @Composable fun fnKey() = if (isSystemInDarkTheme()) Color(0xFF2A2A2C) else Color(0xFFD1D1D8)
    @Composable fun segment() = if (isSystemInDarkTheme()) Color(0xFF2A2A2C) else Color(0xFFD6D6DC)
}

/** フォーカスブロック: 大きな角丸の白いカード。背景(#EFEFF4)とのコントラストで内容を目立たせる。 */
@Composable
fun FocusBlock(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Box(modifier.clip(RoundedCornerShape(OneUi.RadiusBlock)).background(color)) { content() }
}

/**
 * One UI のスイッチ。OFFは枠線のみ+グレーのつまみ、ONは青いトラック+白いつまみ(角丸の完全な円形)。
 * Material3 の Switch(つまみアイコン付き)とは見た目が違うため自前で描く。
 */
@Composable
fun OneUiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outline
    val x = animateDpAsState(if (checked) 22.dp else 0.dp, label = "thumb")
    Box(
        modifier
            .size(50.dp, 28.dp)
            .clip(CircleShape)
            .background(if (checked) primary else Color.Transparent)
            .border(if (checked) 0.dp else 2.dp, off, CircleShape)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = x.value)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFFFAFAFA) else off),
        )
    }
}

/** One UI のスライダー: 太めの丸いトラックと、青い円形のつまみ。 */
@Composable
fun OneUiSlider(
    value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    var w by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val thumb = 22.dp
    val thumbPx = with(density) { thumb.toPx() }
    val span = range.endInclusive - range.start
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    fun at(x: Float) = range.start + ((x - thumbPx / 2) / (w - thumbPx)).coerceIn(0f, 1f) * span

    Box(
        modifier
            .fillMaxWidth().height(36.dp)
            .onSizeChanged { w = it.width.toFloat() }
            .pointerInput(range) { detectTapGestures { o -> onValueChange(at(o.x)) } }
            .pointerInput(range) { detectHorizontalDragGestures { ch, _ -> ch.consume(); onValueChange(at(ch.position.x)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(track))
        Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(CircleShape).background(primary))
        Box(
            Modifier.offset { IntOffset(((w - thumbPx) * frac).toInt(), 0) }
                .size(thumb).clip(CircleShape).background(primary),
        )
    }
}

/** サブタブ。選択中は完全な角丸(ピル)の淡い青、他は文字のみ。 */
@Composable
fun PillTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .clip(CircleShape)
            .background(if (selected) primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** コンテインドボタン(ピル型)。同一画面でフラットボタンと混在させない(公式ガイド)。 */
@Composable
fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .clip(CircleShape)
            .background(primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = primary, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/** 設定の小見出し(One UIは青い小さな文字)。 */
@Composable
fun SubHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** 設定のグループ: 角丸のカードに行を縦に並べる(行の間は細い区切り線)。 */
@Composable
fun SettingGroup(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(OneUi.RadiusMedium))
            .background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 4.dp),
    ) { content() }
}

@Composable
fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}
