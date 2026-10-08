package com.datamonitor.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import java.util.Calendar

enum class NetworkKind(val label: String, val labelEn: String, val statsType: Int) {
    WIFI("Wi-Fi", "Wi-Fi", ConnectivityManager.TYPE_WIFI),
    MOBILE_2G("2G", "2G", ConnectivityManager.TYPE_MOBILE),
    MOBILE_3G("3G", "3G", ConnectivityManager.TYPE_MOBILE),
    MOBILE_4G("4G", "4G", ConnectivityManager.TYPE_MOBILE),
    MOBILE_5G("5G", "5G", ConnectivityManager.TYPE_MOBILE),
    MOBILE_OTHER("Мобільна мережа", "Mobile data", ConnectivityManager.TYPE_MOBILE),
    NONE("Немає з'єднання", "No connection", -1)
}

/**
 * Все, що пов'язане з визначенням поточної мережі та статистикою трафіку.
 *
 * ВАЖЛИВО: публічного Android API для читання балансу/дати поповнення тарифу
 * оператора не існує (це закрита інформація оператора). Тому "авто-визначення"
 * тут — це best-effort спроба (через TelephonyManager), яка на переважній
 * більшості пристроїв поверне null. У такому разі додаток переходить на
 * ручний ввід користувачем (planTotalGB / currentRemainingGB у Prefs).
 */
object NetworkUsageHelper {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun todayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getActiveNetworkKind(context: Context): NetworkKind {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkKind.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkKind.NONE

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getCellularKind(context)
            else -> NetworkKind.NONE
        }
    }

    private fun getCellularKind(context: Context): NetworkKind {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            val type = tm.dataNetworkType
            when (type) {
                TelephonyManager.NETWORK_TYPE_NR -> NetworkKind.MOBILE_5G
                TelephonyManager.NETWORK_TYPE_LTE -> NetworkKind.MOBILE_4G
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> NetworkKind.MOBILE_3G
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT -> NetworkKind.MOBILE_2G
                else -> NetworkKind.MOBILE_OTHER
            }
        } catch (e: SecurityException) {
            NetworkKind.MOBILE_OTHER
        }
    }

    /**
     * Повертає використані байти за [start]..[end] для заданого типу мережі.
     * -1 означає відсутність доступу до статистики (треба Usage Access).
     */
    fun queryUsageBytes(context: Context, statsType: Int, start: Long, end: Long): Long {
        if (!hasUsageAccess(context)) return -1
        return try {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val subscriberId = if (statsType == ConnectivityManager.TYPE_MOBILE) {
                getSubscriberIdSafe(context)
            } else null

            val bucket = NetworkStats.Bucket()
            var total = 0L
            val stats = nsm.querySummary(statsType, subscriberId, start, end)
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                total += bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            total
        } catch (e: Exception) {
            -1
        }
    }

    // Спроба отримати subscriberId. На звичайному (непривілейованому) застосунку
    // з API 29+ це майже завжди недоступно. Повертаємо null у разі невдачі —
    // це важливо: NetworkStatsManager при null subscriberId для власника
    // дозволу Usage Access повертає АГРЕГОВАНУ статистику по мобільній мережі
    // без фільтрації. Порожній рядок "" натомість трактується як конкретний
    // (неіснуючий) subscriberId і повертає 0 байт замість реальних даних.
    private fun getSubscriberIdSafe(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION", "MissingPermission")
            tm.subscriberId
        } catch (e: Exception) {
            null
        }
    }

    fun bytesToGB(bytes: Long): Double = bytes / 1_000_000_000.0

    fun formatGB(gb: Double): String = String.format("%.2f", gb)
}
