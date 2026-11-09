package io.github.ganondorofu.deskport

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One UI の値。
 * 出典: Samsung "One UI Design Guidelines"(design.samsung.com) 公式PDF
 *  - 配色 Primary #0381fe / Primary dark #0072de(ライト) / #3e91ff(ダーク)、Control activated #3e91ff、White #fafafa、Black #000000(ライト)/#080808(ダーク)
 *  - Thumbnail/フォーカスブロックの角丸 26dp / 20dp / 12dp
 *  - 余白は左右24dp以上を推奨
 *  - 背景 #EFEFF4 は同PDF内のライト画面の画像から実測した値(文書内に16進数の記載は無い)
 *  - ダークのカード色 #252525 は推定値(文書に記載なし)
 */
object OneUi {
    val Blue = Color(0xFF0381FE)
    val BlueActive = Color(0xFF3E91FF)
    val LightBackground = Color(0xFFEFEFF4)
    val DarkSurface = Color(0xFF252525)       // 推定
    val RadiusBlock = 26.dp
    val RadiusMedium = 20.dp
    val RadiusSmall = 12.dp
    val SideMargin = 16.dp                    // 公式の推奨は24dp以上。パネルは画面幅の都合で16dp、見出しは24dpにしている
    val TitleMargin = 24.dp
}

// Material3 の既定色(紫がかった tonal surface 等)が残らないよう、すべての役割を明示的に上書きする
private val Light = lightColorScheme(
    primary = OneUi.Blue,
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFD9EAFF),
    onPrimaryContainer = OneUi.Blue,
    secondary = OneUi.Blue,
    secondaryContainer = Color(0xFFD9EAFF),
    onSecondaryContainer = OneUi.Blue,
    tertiary = OneUi.Blue,
    background = OneUi.LightBackground,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE6E6EC),
    onSurfaceVariant = Color(0xFF7A7A7F),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = OneUi.LightBackground,
    outline = Color(0xFFBDBDC2),
    outlineVariant = Color(0xFFE0E0E5),
    scrim = Color(0x66000000),
)

private val Dark = darkColorScheme(
    primary = OneUi.BlueActive,
    onPrimary = Color(0xFF080808),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = OneUi.BlueActive,
    secondary = OneUi.BlueActive,
    secondaryContainer = Color(0xFF1B3A66),
    onSecondaryContainer = OneUi.BlueActive,
    tertiary = OneUi.BlueActive,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFAFAFA),
    surface = OneUi.DarkSurface,
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = Color(0xFFA0A0A5),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = OneUi.DarkSurface,
    surfaceContainerLow = OneUi.DarkSurface,
    surfaceContainer = OneUi.DarkSurface,
    surfaceContainerHigh = OneUi.DarkSurface,
    surfaceContainerHighest = Color(0xFF000000),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF3A3A3C),
    scrim = Color(0x99000000),
)

private val OneUiShapes = Shapes(
    extraSmall = RoundedCornerShape(OneUi.RadiusSmall),
    small = RoundedCornerShape(OneUi.RadiusSmall),
    medium = RoundedCornerShape(OneUi.RadiusMedium),
    large = RoundedCornerShape(OneUi.RadiusBlock),
    extraLarge = RoundedCornerShape(OneUi.RadiusBlock),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeskportTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = OneUiShapes,
    ) {
        // Material の波紋は強い色で目立つので、One UI のような控えめなグレーのハイライトにする
        CompositionLocalProvider(
            LocalRippleConfiguration provides RippleConfiguration(color = Color(0xFF808080)),
            content = content,
        )
    }
}
