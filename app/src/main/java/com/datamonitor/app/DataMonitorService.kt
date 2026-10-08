package com.datamonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataMonitorService : Service() {

    companion object {
        const val CHANNEL_ID_ONGOING = "usage_monitor_ongoing"
        const val CHANNEL_ID_ALERTS = "usage_monitor_alerts"
        const val NOTIF_ID_ONGOING = 1001
        const val NOTIF_ID_ALERT_MOBILE = 2001
        const val NOTIF_ID_ALERT_WIFI = 2002
        const val UPDATE_INTERVAL_MS = 20_000L
        const val SPEED_INTERVAL_MS = 1_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var connectivityManager: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Кеш вмісту сповіщення: важкі запити статистики раз на 20 с,
    // а секундний тік швидкості лише перемальовує сповіщення з кешу.
    private var cachedTitle = "Моніторинг трафіку"
    private var cachedText = "Запуск моніторингу…"
    private var cachedMax = 0
    private var cachedProgress = 0
    private var cachedNoAccess = false

    // Вимірювання швидкості
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSpeedTime = 0L
    private var curDown = 0.0 // байт/с
    private var curUp = 0.0

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    private val speedRunnable = object : Runnable {
        override fun run() {
            if (prefs.speedEnabled) {
                measureSpeed()
                render()
                handler.postDelayed(this, SPEED_INTERVAL_MS)
            } else {
                // швидкість вимкнена — рідко перевіряємо, чи її не ввімкнули знову
                lastSpeedTime = 0L
                handler.postDelayed(this, 3_000L)
            }
        }
    }

    // Коли екран вимкнений — не міряємо швидкість щосекунди (економія батареї)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(speedRunnable)
                Intent.ACTION_SCREEN_ON -> {
                    lastSpeedTime = 0L
                    handler.removeCallbacks(speedRunnable)
                    handler.post(speedRunnable)
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createChannels()
        startForeground(NOTIF_ID_ONGOING, buildBaseNotification("Запуск моніторингу…"))
        registerNetworkCallback()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        handler.post(updateRunnable)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) handler.post(speedRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(speedRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateNotification()
            override fun onLost(network: Network) = updateNotification()
            override fun onCapabilitiesChanged(
                network: Network,
                caps: android.net.NetworkCapabilities
            ) = updateNotification()
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val ongoing = NotificationChannel(
            CHANNEL_ID_ONGOING, "Використання трафіку", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Постійне сповіщення з використанням даних сьогодні" }
        val alerts = NotificationChannel(
            CHANNEL_ID_ALERTS, "Попередження про ліміт", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Сповіщення при перевищенні встановленого порогу МБ" }
        nm.createNotificationChannel(ongoing)
        nm.createNotificationChannel(alerts)
    }

    private fun buildBaseNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Моніторинг трафіку")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // Швидкість = різниця лічильників байт між двома тіками / час між ними
    private fun measureSpeed() {
        var rx = TrafficStats.getTotalRxBytes()
        var tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong()) rx = 0L
        if (tx == TrafficStats.UNSUPPORTED.toLong()) tx = 0L
        val now = SystemClock.elapsedRealtime()

        if (lastSpeedTime != 0L) {
            val dt = (now - lastSpeedTime) / 1000.0
            if (dt > 0) {
                curDown = maxOf(0.0, (rx - lastRx) / dt)
                curUp = maxOf(0.0, (tx - lastTx) / dt)
            }
        } else {
            curDown = 0.0
            curUp = 0.0
        }
        lastRx = rx
        lastTx = tx
        lastSpeedTime = now
    }

    // Збирає й показує сповіщення з кешованого вмісту + поточної швидкості.
    // Використовуємо системний Notification.Builder, бо він приймає Icon з bitmap
    // для малої іконки (саме так іконка статус-бару стає "спідометром").
    private fun render() {
        val nm = getSystemService(NotificationManager::class.java)

        val builder = Notification.Builder(this, CHANNEL_ID_ONGOING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (cachedNoAccess) {
            val settingsIntent = PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java).putExtra("open_usage_access", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Потрібен доступ до статистики")
                .setContentText("Натисніть, щоб надати доступ до використання даних")
                .setContentIntent(settingsIntent)
        } else {
            builder.setContentTitle(cachedTitle)
                .setContentText(cachedText)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            if (cachedMax > 0) builder.setProgress(cachedMax, cachedProgress, false)

            if (prefs.speedEnabled) {
                builder.setSmallIcon(Icon.createWithBitmap(SpeedIconRenderer.render(curDown)))
                builder.setSubText(
                    "↓ ${SpeedIconRenderer.label(curDown)}  ↑ ${SpeedIconRenderer.label(curUp)}"
                )
            } else {
                builder.setSmallIcon(R.drawable.ic_notification)
            }
        }

        nm.notify(NOTIF_ID_ONGOING, builder.build())
    }

    // Важка частина (NetworkStatsManager) — раз на 20 с або при зміні мережі.
    // Результат кладемо в кеш і викликаємо render().
    private fun updateNotification() {
        if (!NetworkUsageHelper.hasUsageAccess(this)) {
            cachedNoAccess = true
            render()
            return
        }
        cachedNoAccess = false

        val kind = NetworkUsageHelper.getActiveNetworkKind(this)
        val todayStart = NetworkUsageHelper.todayStartMillis()
        val now = System.currentTimeMillis()

        when (kind) {
            NetworkKind.NONE -> {
                cachedTitle = "Немає з'єднання"
                cachedText = "Очікування підключення до мережі…"
                cachedMax = 0
            }
            NetworkKind.WIFI -> {
                val usedBytes = NetworkUsageHelper.queryUsageBytes(
                    this, ConnectivityManager.TYPE_WIFI, todayStart, now
                )
                val usedGB = NetworkUsageHelper.bytesToGB(maxOf(usedBytes, 0))
                cachedTitle = "Wi-Fi"
                cachedText = "Сьогодні використано: ${NetworkUsageHelper.formatGB(usedGB)} ГБ"
                cachedMax = 0
                checkThresholdAndAlert(
                    isMobile = false,
                    usedBytesToday = usedBytes,
                    thresholdMB = prefs.wifiThresholdMB,
                    networkLabel = "Wi-Fi"
                )
            }
            else -> { // будь-який мобільний варіант
                val usedTodayBytes = NetworkUsageHelper.queryUsageBytes(
                    this, ConnectivityManager.TYPE_MOBILE, todayStart, now
                )
                val usedSinceSetBytes = NetworkUsageHelper.queryUsageBytes(
                    this, ConnectivityManager.TYPE_MOBILE, prefs.remainingSetAtMillis, now
                )
                val usedTodayGB = NetworkUsageHelper.bytesToGB(maxOf(usedTodayBytes, 0))
                val usedSinceSetGB = NetworkUsageHelper.bytesToGB(maxOf(usedSinceSetBytes, 0))
                val remainingGB = (prefs.currentRemainingGB - usedSinceSetGB).coerceAtLeast(0.0)

                cachedTitle = "Мобільна мережа · ${kind.label}"
                cachedText = "Сьогодні: ${NetworkUsageHelper.formatGB(usedTodayGB)} ГБ" +
                    " · Залишилось: ${NetworkUsageHelper.formatGB(remainingGB)} ГБ"
                if (prefs.planTotalGB > 0f) {
                    val maxUnits = (prefs.planTotalGB * 100).toInt().coerceAtLeast(1)
                    cachedMax = maxUnits
                    cachedProgress = (remainingGB * 100).toInt().coerceIn(0, maxUnits)
                } else {
                    cachedMax = 0
                }
                checkThresholdAndAlert(
                    isMobile = true,
                    usedBytesToday = usedTodayBytes,
                    thresholdMB = prefs.mobileThresholdMB,
                    networkLabel = "Мобільна мережа (${kind.label})"
                )
            }
        }

        render()
    }

    private fun todayDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun checkThresholdAndAlert(
        isMobile: Boolean,
        usedBytesToday: Long,
        thresholdMB: Int,
        networkLabel: String
    ) {
        if (thresholdMB <= 0 || usedBytesToday < 0) return
        val usedMB = usedBytesToday / 1_000_000.0
        if (usedMB < thresholdMB) return

        val today = todayDateString()
        val alreadySent = if (isMobile) prefs.mobileAlertSentDate else prefs.wifiAlertSentDate
        if (alreadySent == today) return

        if (isMobile) prefs.mobileAlertSentDate = today else prefs.wifiAlertSentDate = today

        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Перевищено поріг: $networkLabel")
            .setContentText("Використано ${thresholdMB} МБ або більше сьогодні")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(if (isMobile) NOTIF_ID_ALERT_MOBILE else NOTIF_ID_ALERT_WIFI, n)
    }
}
