package com.datamonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.datamonitor.app.ui.theme.DataMonitorTheme
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val phonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        if (intent.getBooleanExtra("open_usage_access", false)) {
            NetworkUsageHelper.openUsageAccessSettings(this)
        }

        requestRuntimePermissionsIfNeeded()

        setContent {
            DataMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(prefs = prefs)
                }
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }
}

@Composable
fun AppScreen(prefs: Prefs) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var lang by remember { mutableStateOf(prefs.language) }
    val isUk = lang == "uk"

    var hasUsageAccess by remember { mutableStateOf(NetworkUsageHelper.hasUsageAccess(context)) }
    var monitoring by remember { mutableStateOf(prefs.monitoringEnabled) }

    var networkLabel by remember { mutableStateOf("") }
    var usedTodayText by remember { mutableStateOf("") }

    var planTotal by remember { mutableStateOf(if (prefs.planTotalGB > 0f) prefs.planTotalGB.toString() else "") }
    var currentRemaining by remember { mutableStateOf(if (prefs.currentRemainingGB > 0f) prefs.currentRemainingGB.toString() else "") }
    var mobileThreshold by remember { mutableStateOf(if (prefs.mobileThresholdMB > 0) prefs.mobileThresholdMB.toString() else "") }
    var wifiThreshold by remember { mutableStateOf(if (prefs.wifiThresholdMB > 0) prefs.wifiThresholdMB.toString() else "") }

    // Живе оновлення статусу на екрані, поки він відкритий
    LaunchedEffect(Unit) {
        while (true) {
            hasUsageAccess = NetworkUsageHelper.hasUsageAccess(context)
            val kind = NetworkUsageHelper.getActiveNetworkKind(context)
            networkLabel = if (isUk) kind.label else kind.labelEn
            if (hasUsageAccess) {
                val start = NetworkUsageHelper.todayStartMillis()
                val now = System.currentTimeMillis()
                val statsType = if (kind == NetworkKind.WIFI) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE
                val bytes = NetworkUsageHelper.queryUsageBytes(context, statsType, start, now)
                usedTodayText = if (bytes >= 0)
                    NetworkUsageHelper.formatGB(NetworkUsageHelper.bytesToGB(bytes)) + " ГБ"
                else "—"
            }
            delay(5000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isUk) "Моніторинг трафіку" else "Data Monitor",
                fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = {
                lang = if (isUk) "en" else "uk"
                prefs.language = lang
            }) {
                Text(if (isUk) "EN" else "UA")
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (isUk) "Поточна мережа: $networkLabel" else "Current network: $networkLabel")
                Text(if (isUk) "Використано сьогодні: $usedTodayText" else "Used today: $usedTodayText")
            }
        }

        if (!hasUsageAccess) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isUk) "Немає доступу до статистики використання даних. Без нього додаток не може порахувати трафік."
                        else "No access to usage stats. Without it the app can't calculate data usage."
                    )
                    Button(onClick = { NetworkUsageHelper.openUsageAccessSettings(context) }) {
                        Text(if (isUk) "Надати доступ" else "Grant access")
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isUk) "Ліміт мобільного тарифу" else "Mobile plan limit",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isUk)
                        "Автоматично дізнатись баланс/дату поповнення в оператора неможливо — Android не надає такого API. Заповніть поля вручну."
                    else
                        "Android has no public API to read a carrier's data balance or top-up date automatically. Please fill these in manually.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = planTotal,
                    onValueChange = { planTotal = it },
                    label = { Text(if (isUk) "Загальний обсяг у тарифі (ГБ)" else "Total plan volume (GB)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = currentRemaining,
                    onValueChange = { currentRemaining = it },
                    label = { Text(if (isUk) "Скільки зараз залишилось (ГБ)" else "Current remaining (GB)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        prefs.planTotalGB = planTotal.toFloatOrNull() ?: prefs.planTotalGB
                        prefs.currentRemainingGB = currentRemaining.toFloatOrNull() ?: prefs.currentRemainingGB
                        prefs.remainingSetAtMillis = System.currentTimeMillis()
                    }) {
                        Text(if (isUk) "Зберегти" else "Save")
                    }
                    OutlinedButton(onClick = {
                        prefs.resetTopUp()
                        currentRemaining = prefs.currentRemainingGB.toString()
                    }) {
                        Text(if (isUk) "Поповнення (скинути ліміт)" else "Top-up (reset limit)")
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isUk) "Порогові сповіщення (МБ за сьогодні)" else "Threshold alerts (MB today)",
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = mobileThreshold,
                    onValueChange = { mobileThreshold = it },
                    label = { Text(if (isUk) "Поріг для мобільної мережі (МБ)" else "Mobile threshold (MB)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = wifiThreshold,
                    onValueChange = { wifiThreshold = it },
                    label = { Text(if (isUk) "Поріг для Wi-Fi (МБ)" else "Wi-Fi threshold (MB)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    prefs.mobileThresholdMB = mobileThreshold.toIntOrNull() ?: 0
                    prefs.wifiThresholdMB = wifiThreshold.toIntOrNull() ?: 0
                }) {
                    Text(if (isUk) "Зберегти пороги" else "Save thresholds")
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isUk) "Моніторинг активний" else "Monitoring active")
                    Switch(checked = monitoring, onCheckedChange = { checked ->
                        monitoring = checked
                        prefs.monitoringEnabled = checked
                        val svcIntent = Intent(context, DataMonitorService::class.java)
                        if (checked) {
                            ContextCompat.startForegroundService(context, svcIntent)
                        } else {
                            context.stopService(svcIntent)
                        }
                    })
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var speedEnabled by remember { mutableStateOf(prefs.speedEnabled) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isUk) "Швидкість інтернету в іконці" else "Speed in status bar icon",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = speedEnabled, onCheckedChange = {
                        speedEnabled = it
                        prefs.speedEnabled = it
                    })
                }
                Text(
                    if (isUk) "Число у значку сповіщення — швидкість завантаження (KB/MB за секунду). Оновлюється раз на секунду, поки екран увімкнений."
                    else "The number in the notification icon is the download speed (KB/MB per second). Updates every second while the screen is on.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
