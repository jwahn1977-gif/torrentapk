package com.raontorrent.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.raontorrent.MainActivity

/**
 * 화면을 꺼도 다운로드가 계속되도록 프로세스를 살려 두는 포그라운드 서비스.
 * 진행 중인(일시정지 아닌) 토렌트가 없으면 스스로 종료한다.
 */
class TorrentService : Service() {
  private val handler = Handler(Looper.getMainLooper())

  private val ticker =
      object : Runnable {
        override fun run() {
          if (!update()) return
          handler.postDelayed(this, 2000)
        }
      }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureChannel()
    // startForegroundService()로 호출될 때마다 startForeground()를 다시 불러야 안전하다
    val n = build("토렌트 준비 중…")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTI_ID, n)
    }
    try {
      TorrentEngine.start(this)
    } catch (t: Throwable) {
      Log.w("RaonTorrent", "engine start failed", t)
    }
    handler.removeCallbacks(ticker)
    handler.postDelayed(ticker, 3000)
    return START_NOT_STICKY
  }

  /** @return 계속 돌아야 하면 true */
  private fun update(): Boolean {
    val s = TorrentEngine.summary()
    if (s.active == 0) {
      stopSelf()
      return false
    }
    val text =
        if (s.downloading > 0) "다운로드 ${s.downloading}개 · ↓ ${rate(s.downRate)} · ↑ ${rate(s.upRate)}"
        else "다운로드 완료 · 시딩 중 ↑ ${rate(s.upRate)}"
    getSystemService(NotificationManager::class.java).notify(NOTI_ID, build(text))
    return true
  }

  /** Android 15+: dataSync 포그라운드 서비스 시간 한도(하루 6시간) 도달 시 */
  override fun onTimeout(startId: Int, fgsType: Int) {
    stopSelf()
  }

  override fun onDestroy() {
    handler.removeCallbacks(ticker)
    super.onDestroy()
  }

  private fun ensureChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(
          NotificationChannel(CHANNEL, "다운로드 상태", NotificationManager.IMPORTANCE_LOW))
    }
  }

  private fun build(text: String): Notification {
    val pi =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("RaonTorrent")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(pi)
        .build()
  }

  companion object {
    private const val CHANNEL = "downloads"
    private const val NOTI_ID = 1001

    fun rate(bps: Long): String =
        when {
          bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / 1048576.0)
          bps >= 1024 -> String.format("%.0f KB/s", bps / 1024.0)
          else -> "$bps B/s"
        }

    /** 앱이 화면에 있을 때 호출 (백그라운드 시작 제한 회피) */
    fun refresh(ctx: Context) {
      try {
        ctx.startForegroundService(Intent(ctx, TorrentService::class.java))
      } catch (t: Throwable) {
        Log.w("RaonTorrent", "service start failed", t)
      }
    }
  }
}
