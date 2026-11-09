package io.github.ganondorofu.deskport

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * スマホのカメラ映像を、PC の仮想カメラへ送る(PC の Deskport ホストで、カメラを有効にしてあるとき、PC のカメラとして使える)。
 * 1枚ずつ JPEG にして、UDP で送る(1枚ごとに独立しているので、取りこぼしても、次の1枚で直る)。
 * アプリが画面に出ている間だけ動く(バックグラウンドでは、Android がカメラを止める)。
 */
object CameraSender {
    var running by mutableStateOf(false)
        private set
    var front by mutableStateOf(false)
        private set

    /** メニューを開いている間だけ、送っている映像の縮小版を作る(確認用。回転は、ホストへ送るのと同じ向きに直してある)。 */
    @Volatile var previewWanted = false
    var preview by mutableStateOf<android.graphics.Bitmap?>(null)
        private set
    private var lastPreview = 0L

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var frameId = 0
    /** 送り先の受信機。ディスプレイを切り替えても、途中で変えない(その受信機が終わったときだけ、選び直す)。 */
    private var pinned: NativeReceiver? = null
    /** 最後に送った時刻(状態の表示用。1秒に1回までしか更新しない)。 */
    var lastSentAt by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    private var lastSent = 0L

    fun start(ctx: Context, lifecycleOwner: LifecycleOwner, useFront: Boolean = front) {
        owner = lifecycleOwner
        front = useFront
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(960, 540))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { img -> try { send(img) } finally { img.close() } }
                p.bindToLifecycle(lifecycleOwner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                running = true
            } catch (e: Exception) {
                Log.w("CameraSender", "カメラを開始できません: ${e.message}")
                running = false
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() {
        running = false
        pinned = null
        lastSentAt = 0L
        preview = null
        try { provider?.unbindAll() } catch (_: Exception) {}
    }

    fun switchCamera(ctx: Context) {
        val o = owner ?: return
        if (running) start(ctx, o, !front) else front = !front
    }

    private fun send(img: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastSent < 60) return                  // 15fps まで
        var rx = pinned?.takeIf { Stream.isLive(it) }
        if (rx == null) { rx = Stream.nativeInput; pinned = rx }
        if (rx == null) return
        lastSent = now
        val jpeg = toJpeg(img) ?: return
        // 回転は、ホストが行う(img.imageInfo.rotationDegrees = 正しい向きにするために、時計回りに回す角度)
        rx.camera(jpeg, (img.imageInfo.rotationDegrees / 90) and 3, frameId++)
        if (now - lastSentAt > 1000) lastSentAt = now
        if (previewWanted && now - lastPreview > 400) {
            lastPreview = now
            try {
                val opt = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opt)?.let { b ->
                    val deg = img.imageInfo.rotationDegrees
                    preview = if (deg == 0) b else android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
                }
            } catch (_: Exception) {}
        }
    }

    private fun toJpeg(img: ImageProxy): ByteArray? {
        val w = img.width; val h = img.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        // Y(行ごとに、余白を除いてコピー)
        var pos = 0
        for (row in 0 until h) {
            y.buffer.position(row * y.rowStride)
            y.buffer.get(nv21, pos, w); pos += w
        }
        // VU(NV21 は、V と U が交互)
        val cw = w / 2; val ch = h / 2
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val vi = row * v.rowStride + col * v.pixelStride
                val ui = row * u.rowStride + col * u.pixelStride
                nv21[pos++] = v.buffer.get(vi)
                nv21[pos++] = u.buffer.get(ui)
            }
        }
        val out = ByteArrayOutputStream(w * h / 8)
        return if (YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 60, out)) out.toByteArray() else null
    }
}
