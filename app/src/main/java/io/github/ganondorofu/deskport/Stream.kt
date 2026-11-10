package io.github.ganondorofu.deskport

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 映像配信の流れ: 最も速いアドレスを選ぶ → ディスプレイごとに UDP で接続 → 復号して表示。
 * 画面から離れても(戻る/ホーム)配信は続き、画面に戻ると同じ配信が見える。
 */
object Stream {
    enum class State { IDLE, STARTING, STREAMING, FAILED }

    var state by mutableStateOf(State.IDLE)
    var message by mutableStateOf("")
    /** いま配信しているホストのディスプレイ(0始まり)。 */
    var display by mutableStateOf(0)
    private var baseHost: Host? = null
    private var switchPending by mutableStateOf(false)
    /** ディスプレイの切り替え中(前のディスプレイの最後の1枚を見せないために、映像の面を隠す)。 */
    val switching: Boolean get() = switchPending && state != State.STREAMING
    /** 配信しているとき true。 */
    var native = false
    private val lock = Any()
    private val receivers = HashMap<Int, NativeReceiver>()
    /** いま映像を受けているディスプレイ(自作ホスト)。複数あれば、同時に受けている。 */
    val nativeDisplays = androidx.compose.runtime.mutableStateListOf<Int>()
    private val awaiting = HashSet<Int>()       // 受け始めを待っている新しい配信
    private val pendingStop = HashSet<Int>()    // 新しい配信が始まったら止める、前の配信
    /** ディスプレイごとのデコーダ(自作ホスト用)。 */
    val decoders = Array(4) { VideoDecoder() }
    val decoder get() = decoders[0]
    /** 状況の表示用: 受けている配信のうち、いちばん若い番号のデコーダ。 */
    val primaryDecoder: VideoDecoder get() = decoders[nativeDisplays.minOrNull() ?: 0]
    /** 自作ホストへ操作を送る先。k を指定すれば、そのディスプレイの配信(なければ最初の配信)。 */
    fun nativeInputFor(k: Int? = null): NativeReceiver? = synchronized(lock) {
        (k?.let { receivers[it] }) ?: receivers.entries.minByOrNull { it.key }?.value
    }
    val nativeInput: NativeReceiver? get() = nativeInputFor()
    /** ディスプレイ k への操作の送り先。そのディスプレイを受けていなければ、受けている最初の配信(その番号も返す)。 */
    fun inputTarget(k: Int): Pair<Int, NativeReceiver>? = synchronized(lock) {
        receivers[k]?.let { k to it } ?: receivers.entries.minByOrNull { it.key }?.let { it.key to it.value }
    }
    /** キャンバスの x 座標が、何番目のディスプレイに当たるか。 */
    fun displayOf(x: Float): Int = (x / WIDTH).toInt().coerceIn(0, 3)

    /**
     * キャンバス上の1ディスプレイ分の枠(論理座標。ホストの実際の解像度とは無関係)。
     * 実際のディスプレイは、縦横比を保ってこの枠に収め、中央に置く。
     */
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val FPS = 60

    /** ホストが知らせた、各ディスプレイの実際の解像度(受け取るまでは枠と同じ)。 */
    private val hostW = androidx.compose.runtime.mutableStateListOf(WIDTH, WIDTH, WIDTH, WIDTH)
    private val hostH = androidx.compose.runtime.mutableStateListOf(HEIGHT, HEIGHT, HEIGHT, HEIGHT)

    fun setSize(k: Int, w: Int, h: Int) {
        if (k in 0..3 && w > 0 && h > 0) { hostW[k] = w; hostH[k] = h }
    }

    private fun fit(k: Int): Float = minOf(WIDTH / hostW[k].toFloat(), HEIGHT / hostH[k].toFloat())

    /** 枠の中で映像が占める範囲 [左, 上, 幅, 高さ](枠の左上が原点)。 */
    fun videoRect(k: Int): FloatArray {
        val f = fit(k); val w = hostW[k] * f; val h = hostH[k] * f
        return floatArrayOf((WIDTH - w) / 2f, (HEIGHT - h) / 2f, w, h)
    }

    /** キャンバス上の座標を、ディスプレイ k を基準にした実際のピクセルに直す(範囲の外も、そのまま。隣のディスプレイへ出るため)。 */
    fun toHostFree(k: Int, canvasX: Float, canvasY: Float): Pair<Int, Int> {
        val r = videoRect(k); val f = fit(k)
        return ((canvasX - k * WIDTH - r[0]) / f).toInt().coerceIn(-30000, 30000) to ((canvasY - r[1]) / f).toInt().coerceIn(-30000, 30000)
    }

    /** キャンバス上の座標を、ディスプレイ k の実際のピクセルに直す(映像の外は端に寄せる)。 */
    fun toHost(k: Int, canvasX: Float, canvasY: Float): Pair<Int, Int> {
        val r = videoRect(k); val f = fit(k)
        val x = ((canvasX - k * WIDTH - r[0]) / f).toInt().coerceIn(0, hostW[k] - 1)
        val y = ((canvasY - r[1]) / f).toInt().coerceIn(0, hostH[k] - 1)
        return x to y
    }

    /** ディスプレイ k の実際のピクセルを、キャンバス上の座標に直す。範囲外なら null。 */
    fun fromHost(k: Int, x: Int, y: Int): Pair<Float, Float>? {
        if (x !in 0 until hostW[k] || y !in 0 until hostH[k]) return null
        val r = videoRect(k); val f = fit(k)
        return (k * WIDTH + r[0] + x * f) to (r[1] + y * f)
    }

    /**
     * 1枚だけ表示しているとき、カーソルがその外(別のディスプレイ)にあれば、その向き(表示中のディスプレイの中心から見た、-1〜1 に収めた比率)。表示の中にあれば null。
     * ホストは、各ディスプレイの座標でカーソルの位置を送ってくる(範囲の外も含む)ので、それで向きが分かる。
     */
    var cursorOffscreen by androidx.compose.runtime.mutableStateOf<Pair<Float, Float>?>(null)
        private set

    fun reportCursor(k: Int, x: Int, y: Int) {
        if (nativeDisplays.size != 1 || k !in nativeDisplays) { if (cursorOffscreen != null && nativeDisplays.size != 1) cursorOffscreen = null; return }
        val w = hostW[k]; val h = hostH[k]
        cursorOffscreen = if (x in 0 until w && y in 0 until h) null
        else ((x - w / 2f) / (w / 2f)) to ((y - h / 2f) / (h / 2f))
    }
    fun clearCursorOffscreen() { cursorOffscreen = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** baseHost に接続する。 */
    /** ロック画面・UAC 対応の補助プロセスのポート(PC で有効にしたときだけ待ち受ける。鍵は通常と同じ)。 */
    const val SECURE_PORT = 49001

    /** いま、ロック画面・UAC も操作できる接続か。 */
    val usingSecure: Boolean get() = baseHost?.nativePort == SECURE_PORT

    /** 専用の接続(補助プロセス)が使えなくなって、通常の接続に戻したか。 */
    var secureFallback by mutableStateOf(false)
        private set
    /** この接続で、つなぎ直した回数(接続の品質の目安)。 */
    var reconnects by mutableIntStateOf(0)
        private set
    private var normalHost: Host? = null
    /** いまの接続先(遅延の測定などに使う)。 */
    val currentHost: Host? get() = baseHost
    /** この受信機が、いま生きているか。 */
    fun isLive(r: NativeReceiver): Boolean = synchronized(lock) { receivers.containsValue(r) }

    fun start(baseHost0: Host, display: Int = 0) {
        if (state == State.STARTING || state == State.STREAMING) return
        // 設定は要らない: PC で、ロック画面・UAC への対応が有効なら補助プロセスが応えるので、それを使う(下の job)。無効なら、通常のポート
        val baseHost = baseHost0
        state = State.STARTING; message = "接続しています…"
        this.baseHost = baseHost; this.display = display
        normalHost = baseHost0; secureFallback = false; reconnects = 0
        job = scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val resolved = chooseTarget(baseHost0)
            Log.i("Stream", "接続先を決めました: ${resolved.address}:${resolved.nativePort}(${android.os.SystemClock.elapsedRealtime() - t0}ms)")
            startNative(resolved, LayoutState.selected.takeIf { it in 0 until resolved.displayCount }?.let { setOf(it) } ?: (0 until resolved.displayCount).toSet())
        }
    }

    private fun startNative(h: Host, want: Set<Int>) {
        native = true
        CursorShapes.clear()
        val kbps = StreamSettings.kbps(want.size)
        synchronized(lock) { awaiting.clear(); awaiting.addAll(want) }
        want.sorted().forEach { openNative(h, it, kbps) }
    }

    private fun openNative(h: Host, k: Int, kbps: Int) {
        val r = NativeReceiver(
            h.address.also { android.util.Log.i("Stream", "ディスプレイ$k の接続先: $it:${h.nativePort}") }, h.nativePort, h.token, k, kbps,
            // 音声は、どの配信にも求める(受け手の AudioPlayer が、先に届いた1つだけを鳴らす)。ディスプレイを切り替える間に、音が途切れないように
            StreamSettings.audio, decoders[k],
            onStarted = { onNativeStarted(k) },
            onFailed = { m -> onNativeFailed(k, m) },
            // 映像にはカーソルが写らないので、ホストが送る位置で表示する。自分のディスプレイの範囲内にあるときだけ採用する
            onCursor = { x, y, visible, shape ->
                // 自分のディスプレイの上にあるときだけ採用する(一括表示で、複数の配信が別々の形を報告して、ちらつかないように)
                reportCursor(k, x, y)
                fromHost(k, x, y)?.let { (cx, cy) ->
                    CursorShapes.select(shape)
                    RemoteCursor.visible = true   // ホストが隠していても、手元では出す(指で動かすので、位置が見えないと困る)
                    if (System.currentTimeMillis() - RemoteInput.lastLocalMoveMs > 400) RemoteCursor.set(cx, cy)
                }
            },
        )
        synchronized(lock) { receivers[k] = r }
        if (k !in nativeDisplays) { nativeDisplays.add(k); cursorOffscreen = null }   // 表示するディスプレイが変わったら、前の矢印は消す
        r.start()
    }

    private val retries = HashMap<Int, Int>()

    /** 全部の候補のアドレスに問い合わせて、最も速く応えたものを使う(LAN と VPN の切り替わりに追従する)。1つも応えなければ、そのまま。 */
    private suspend fun resolve(h: Host): Host = NativeProbe.pick(h)?.let { adopt(it) } ?: h

    private fun adopt(r: Host): Host {
        baseHost = r
        Session.host?.takeIf { it.id == r.id }?.let { Session.host = it.copy(address = r.address) }   // ファイル転送なども、同じアドレスを使う
        return r
    }

    /**
     * つなぎ先を決める。全部の候補のアドレス(LAN、Tailscale など)に、同時に問い合わせて、最も速く応えたものを使う
     * (前回のアドレスが使えない場所でも、その返事を待たずに、他の候補へつながる)。
     * そのあと、選んだアドレスで、補助プロセス(ロック画面・UAC 対応)にも、少しだけ(最大 0.3 秒)問い合わせる。
     */
    private suspend fun chooseTarget(h: Host): Host {
        val r = NativeProbe.pick(h) ?: return h
        val rs = r.copy(nativePort = SECURE_PORT)
        val secure = withTimeoutOrNull(300L) { NativeProbe.rtt(rs) } ?: -1L
        return adopt(if (secure >= 0) rs else r)
    }

    /** 1つの配信が切れたとき: 他の配信は続け、切れたものは数回まで自動でつなぎ直す。 */
    private fun onNativeFailed(k: Int, msg: String) {
        var h = baseHost
        val n = synchronized(lock) { (retries[k] ?: 0) + 1 }.also { synchronized(lock) { retries[k] = it } }
        // 専用の接続(補助プロセス)が、応えたのに、つながらない(止まった、など): 通常の接続に戻す
        val cur = h
        if (cur != null && n >= 2 && cur.nativePort == SECURE_PORT) {
            normalHost?.let { nh -> h = nh; baseHost = h; secureFallback = true }   // 元の候補(アドレス全部)で、通常のポートを探す(resolve が、応えるものを選ぶ)
            Log.w("Stream", "専用の接続につながらないので、通常の接続に戻します")
        }
        val dead = synchronized(lock) { receivers.remove(k) }
        scope.launch(Dispatchers.IO) { dead?.stop() }
        val wanted = nativeDisplays.contains(k)
        if (h == null || !native || !wanted || n > 5) {
            nativeDisplays.remove(k); cursorOffscreen = null
            if (synchronized(lock) { receivers.isEmpty() }) fail(msg)
            return
        }
        Log.w("Stream", "display $k failed ($msg), retry $n")
        reconnects++
        val hh = h
        scope.launch {
            delay(1000L)
            if (native && state != State.IDLE && synchronized(lock) { k !in receivers } && nativeDisplays.contains(k)) {
                openNative(resolve(hh!!), k, StreamSettings.kbps(nativeDisplays.size))
            }
        }
    }

    private fun onNativeStarted(k: Int) {
        synchronized(lock) { retries[k] = 0 }
        state = State.STREAMING; message = ""
        val stop = synchronized(lock) {
            awaiting.remove(k)
            if (awaiting.isEmpty()) pendingStop.toList().also { pendingStop.clear() } else emptyList()
        }
        stopNative(stop)
    }

    private fun stopNative(ks: List<Int>) {
        if (ks.isEmpty()) return
        val rs = synchronized(lock) { ks.mapNotNull { receivers.remove(it) } }
        nativeDisplays.removeAll(ks.toSet()); cursorOffscreen = null
        scope.launch(Dispatchers.IO) {
            // 新しい配信の音が届くまで(最大1.5秒)、古い配信を止めない。切り替えで、音が途切れないように
            if (StreamSettings.audio) {
                val old = rs.map { it.audioId }.toSet()
                val until = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < until && AudioPlayer.currentOwner().let { it == -1L || it in old }) delay(30)
            }
            rs.forEach { it.stop() }
        }
    }

    /**
     * 映像を受けるディスプレイを変える(自作ホスト)。新しい配信が始まってから、要らなくなった配信を止める。
     * 切り替えで映像が止まらず、一括表示では複数を同時に受ける。
     */
    fun showDisplays(want: Set<Int>) {
        val h = baseHost ?: return
        if (!native) return
        val kbps = StreamSettings.kbps(want.size)
        val fresh: Set<Int>
        synchronized(lock) {
            fresh = want - receivers.keys
            pendingStop.clear(); pendingStop.addAll(receivers.keys - want)
            awaiting.clear(); awaiting.addAll(fresh)
        }
        fresh.sorted().forEach { openNative(h, it, kbps) }
        if (fresh.isEmpty()) {
            val stop = synchronized(lock) { pendingStop.toList().also { pendingStop.clear() } }
            stopNative(stop)
        }
    }

    private fun fail(msg: String) { state = State.FAILED; message = msg }

    /** 配信を止め、ホスト側のセッションも終了する。 */
    fun stop() {
        CameraSender.stop()
        RemoteInput.heldMods.clear()   // 押したままのキーは、ホストが配信の終わりに離す
        val wasRunning = state == State.STREAMING || state == State.STARTING
        state = State.IDLE; message = ""
        scope.launch { teardown(wasRunning) }
    }

    private suspend fun teardown(wasRunning: Boolean) {
        job?.cancel()
        run {
            val rs = synchronized(lock) { receivers.values.toList().also { receivers.clear(); awaiting.clear(); pendingStop.clear() } }
            nativeDisplays.clear(); cursorOffscreen = null
            withContext(Dispatchers.IO) { rs.forEach { it.stop() } }
            AudioPlayer.stop()
            native = false
            return
        }
    }

    /** 別のディスプレイに切り替える。いまの配信を止め終えてから、次の配信に接続する(順序が逆になると新しい配信を止めてしまう)。 */
    fun switchDisplay(k: Int) {
        val h = baseHost ?: return
        if (k == display && (state == State.STARTING || state == State.STREAMING)) return
        if (k !in 0 until h.displayCount) return
        val wasRunning = state == State.STREAMING || state == State.STARTING
        switchPending = true
        state = State.STARTING; message = "ディスプレイ ${k + 1} に切り替えています…"; display = k
        scope.launch {
            teardown(wasRunning)
            state = State.IDLE
            start(h, k)
        }
    }

    /** 状況の表示用: 実際に表示できているフレーム数(1秒ごとの差から fps を出す)。 */
    suspend fun fpsLoop(onFps: (Int) -> Unit) {
        // ディスプレイを切り替えるとデコーダが変わり、数え始めの値が違うので、デコーダごとに差を取る(変わった回は 0 とする)
        var dec = primaryDecoder
        var last = dec.framesShown.get()
        while (true) {
            delay(1000)
            val cur = primaryDecoder
            val now = cur.framesShown.get()
            onFps(if (cur === dec) (now - last).toInt().coerceAtLeast(0) else 0)
            dec = cur; last = now
        }
    }
}
