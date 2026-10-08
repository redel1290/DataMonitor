package com.datamonitor.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Обгортка над SharedPreferences для всіх налаштувань додатку.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("data_monitor_prefs", Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString("language", "uk") ?: "uk"
        set(value) = sp.edit().putString("language", value).apply()

    // Загальний обсяг ГБ у тарифі (стабільне значення, використовується при "скиданні" ліміту)
    var planTotalGB: Float
        get() = sp.getFloat("plan_total_gb", 0f)
        set(value) = sp.edit().putFloat("plan_total_gb", value).apply()

    // Скільки ГБ залишалось на момент останнього ручного вводу
    var currentRemainingGB: Float
        get() = sp.getFloat("current_remaining_gb", 0f)
        set(value) = sp.edit().putFloat("current_remaining_gb", value).apply()

    // Момент часу (мс), коли currentRemainingGB було встановлено
    var remainingSetAtMillis: Long
        get() = sp.getLong("remaining_set_at", System.currentTimeMillis())
        set(value) = sp.edit().putLong("remaining_set_at", value).apply()

    // Поріг сповіщення для мобільної мережі, у МБ за сьогодні (0 = вимкнено)
    var mobileThresholdMB: Int
        get() = sp.getInt("mobile_threshold_mb", 0)
        set(value) = sp.edit().putInt("mobile_threshold_mb", value).apply()

    // Поріг сповіщення для Wi-Fi, у МБ за сьогодні (0 = вимкнено)
    var wifiThresholdMB: Int
        get() = sp.getInt("wifi_threshold_mb", 0)
        set(value) = sp.edit().putInt("wifi_threshold_mb", value).apply()

    // Дата (yyyy-MM-dd), за яку вже надсилалось порогове сповіщення (окремо для mobile/wifi)
    var mobileAlertSentDate: String
        get() = sp.getString("mobile_alert_date", "") ?: ""
        set(value) = sp.edit().putString("mobile_alert_date", value).apply()

    var wifiAlertSentDate: String
        get() = sp.getString("wifi_alert_date", "") ?: ""
        set(value) = sp.edit().putString("wifi_alert_date", value).apply()

    var monitoringEnabled: Boolean
        get() = sp.getBoolean("monitoring_enabled", false)
        set(value) = sp.edit().putBoolean("monitoring_enabled", value).apply()

    // Показувати швидкість інтернету в іконці та тексті сповіщення
    var speedEnabled: Boolean
        get() = sp.getBoolean("speed_enabled", true)
        set(value) = sp.edit().putBoolean("speed_enabled", value).apply()

    fun resetTopUp() {
        currentRemainingGB = planTotalGB
        remainingSetAtMillis = System.currentTimeMillis()
    }
}
