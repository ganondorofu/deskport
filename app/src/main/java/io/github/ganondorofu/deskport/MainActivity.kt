package io.github.ganondorofu.deskport

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow

class MainActivity : ComponentActivity() {
    // 他のアプリの「共有」からファイル/文字列が届いたら、ホストへ送る
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        Transfers.handleShare(this, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 設定とホスト一覧の読み込み(プロセスが生きている間の再生成では、読み直しても同じ値)
        Prefs.init(applicationContext)
        // 新しく作られたのに、前の接続が残っている(最近のアプリから消された、通知の「切断」の後など): 最初の画面に戻す
        if (savedInstanceState == null && (Session.host != null || Stream.state != Stream.State.IDLE)) Session.reset(applicationContext)
        if (savedInstanceState == null) Transfers.handleShare(this, intent)
        enableEdgeToEdge()
        // 折りたたみ状態(ヒンジ・半開き)を監視する(角度センサーが無い場合の代替)
        val layoutFlow: Flow<WindowLayoutInfo> = WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
        setContent {
            DeskportTheme {
                val info by layoutFlow.collectAsState(initial = WindowLayoutInfo(emptyList()))
                val angle = rememberHingeAngle()
                // 水平ヒンジ(横向きで折れ目が横線になる状態)の位置。Flexの上下分割に使う
                info.displayFeatures.forEach { f ->
                    if (f is FoldingFeature) Log.d("Fold", "state=${f.state} orient=${f.orientation} bounds=${f.bounds} sep=${f.isSeparating}")
                }
                val hinge = info.displayFeatures.filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.orientation == FoldingFeature.Orientation.HORIZONTAL }
                RemoteApp(
                    detectPosture(info, angle), angle,
                    hingeTopPx = hinge?.bounds?.top, hingeBottomPx = hinge?.bounds?.bottom,
                )
            }
        }
    }
}

/** ヒンジ角度センサー(TYPE_HINGE_ANGLE)の値を取得する。0=閉、180=フラット(想定)。センサーが無ければnull。 */
@Composable
private fun rememberHingeAngle(): Float? {
    val ctx = LocalContext.current
    var angle by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Android標準: 0=閉 / 180=フラット(Pixel Fold系エミュレータはこの値域)。実機の値域は要検証
                angle = e.values[0]
                Log.d("Hinge", "angle=${e.values[0]}")
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        else Log.d("Hinge", "TYPE_HINGE_ANGLE not available")
        onDispose { sm.unregisterListener(listener) }
    }
    return angle
}

/**
 * 幅600dp未満=ストレートバー(カバー画面)。
 * メイン画面では角度が30〜150度ならFlex、それ以外は展開。角度が取れなければ半開き+水平ヒンジでFlex判定。
 */
@Composable
private fun detectPosture(info: WindowLayoutInfo, angle: Float?): Posture {
    val widthDp = LocalConfiguration.current.screenWidthDp
    if (widthDp < 600) return Posture.BAR
    if (angle != null) return if (angle in 30f..150f) Posture.FLEX else Posture.EXPANDED
    val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
    return if (fold != null && fold.state == FoldingFeature.State.HALF_OPENED &&
        fold.orientation == FoldingFeature.Orientation.HORIZONTAL
    ) Posture.FLEX else Posture.EXPANDED
}
