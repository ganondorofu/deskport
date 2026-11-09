package io.github.ganondorofu.deskport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 接続の状態。Activity ではなくプロセス(とフォアグラウンドサービス)が保持するので、
 * ホームに出て別のアプリを使ってから戻っても、読み込み直さずそのまま続きから使える。
 * OS がプロセスごと終了させた場合だけ失われる。
 */
object Session {
    var host by mutableStateOf<Host?>(null)
    var connectedSince by mutableLongStateOf(0L)

    fun connect(ctx: Context, h: Host) {
        host = h
        connectedSince = System.currentTimeMillis()
        HostStore.lastId = h.id
        ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
    }

    fun disconnect(ctx: Context) {
        host = null
        ctx.stopService(Intent(ctx, SessionService::class.java))
    }

    /** 配信ごと完全に終える(通知の「切断」、アプリを最近のアプリから消したとき、など)。残った配信に、次に開いたとき勝手につながらないように。 */
    fun reset(ctx: Context) {
        Stream.stop()
        disconnect(ctx)
    }
}

/**
 * 接続中を示すフォアグラウンドサービス。アプリがバックグラウンドにあってもプロセスが生き残りやすくなる。
 */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** 最近のアプリからアプリを消したとき。接続も配信も、すべて終える。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Session.reset(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Session.reset(this)
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("接続中"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_deskport)
            .setContentTitle(tr("${Session.host?.name ?: "ホスト"} に接続中"))
            .setContentText(tr("タップでアプリに戻る"))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, tr("切断"), stop)
            .build()
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "session"
        const val ID = 1
        const val ACTION_STOP = "io.github.ganondorofu.deskport.STOP"
    }
}
