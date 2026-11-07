package com.datamonitor.app

import android.app.usage.NetworkStats
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AppRow(
    val uid: Int,
    val label: String,
    val icon: ImageBitmap?,
    val todayBytes: Long,
    val speedBps: Double,
    val active: Boolean,
    val recent: Boolean
)

/**
 * Хто споживає інтернет прямо зараз.
 * Швидкість = приріст лічильника застосунку за останні ~10 с (згладжено, бо Android оновлює
 * статистику не плавно). "Активний" — швидкість вище порога; "нещодавно" — був активним
 * протягом останньої хвилини.
 */
object AppTrafficTracker {
    private const val ACTIVE_BPS = 2048.0
    private const val WINDOW_MS = 10_000L
    private const val RECENT_MS = 60_000L

    private class Snap(val t: Long, val totals: Map<Int, Long>)
    private class Info(val label: String, val icon: ImageBitmap?)

    private val history = ArrayDeque<Snap>()
    private val lastActive = HashMap<Int, Long>()
    private val infoCache = HashMap<String, Info>()

    @Synchronized
    fun poll(ctx: Context): List<AppRow> {
        if (!NetworkUsageHelper.hasUsageAccess(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val start = NetworkUsageHelper.todayStartMillis()
        val totals = HashMap<Int, Long>()
        NetworkUsageHelper.queryUidBytes(ctx, ConnectivityManager.TYPE_WIFI, start, now + 60_000, totals)
        NetworkUsageHelper.queryUidBytes(ctx, ConnectivityManager.TYPE_MOBILE, start, now + 60_000, totals)

        if (history.isNotEmpty() && now - history.last().t > 15_000) history.clear()
        history.addLast(Snap(now, totals))
        while (history.size > 2 && now - history.first().t > WINDOW_MS) history.removeFirst()
        val base = history.first()
        val dt = (now - base.t) / 1000.0
        val isUk = Prefs(ctx).language == "uk"

        val rows = ArrayList<AppRow>()
        for ((uid, bytes) in totals) {
            if (bytes <= 0L) continue
            val speed = if (dt >= 1.5) (bytes - (base.totals[uid] ?: 0L)).coerceAtLeast(0L) / dt else 0.0
            val active = speed >= ACTIVE_BPS
            if (active) lastActive[uid] = now
            val recent = !active && now - (lastActive[uid] ?: 0L) < RECENT_MS
            val info = info(ctx, uid, isUk)
            rows.add(AppRow(uid, info.label, info.icon, bytes, speed, active, recent))
        }
        return rows.sortedWith(
            compareByDescending<AppRow> { it.active }
                .thenByDescending { if (it.active) it.speedBps else 0.0 }
                .thenByDescending { it.recent }
                .thenByDescending { if (it.recent) lastActive[it.uid] ?: 0L else 0L }
                .thenByDescending { it.todayBytes }
        )
    }

    private fun info(ctx: Context, uid: Int, isUk: Boolean): Info = infoCache.getOrPut("$uid|$isUk") {
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> Info(if (isUk) "Видалені додатки" else "Removed apps", null)
            NetworkStats.Bucket.UID_TETHERING -> Info(if (isUk) "Роздача інтернету" else "Hotspot / tethering", null)
            else -> {
                val pm = ctx.packageManager
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
                if (pkg == null) {
                    Info(
                        when (uid) {
                            1000 -> if (isUk) "Система Android" else "Android system"
                            else -> if (isUk) "Система ($uid)" else "System ($uid)"
                        }, null
                    )
                } else {
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        Info(pm.getApplicationLabel(ai).toString(), iconOf(pm.getApplicationIcon(ai)))
                    } catch (e: Exception) {
                        Info(pkg, null)
                    }
                }
            }
        }
    }

    private fun iconOf(d: android.graphics.drawable.Drawable): ImageBitmap? = try {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(bmp))
        bmp.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/** Опитує статистику кожні 2 с, але лише поки екран видно. */
@Composable
fun rememberAppRows(): State<List<AppRow>> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf<List<AppRow>>(emptyList()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.value = withContext(Dispatchers.IO) { AppTrafficTracker.poll(context) }
                delay(2000)
            }
        }
    }
    return state
}

private fun fmtBytes(b: Long, uk: Boolean): String = when {
    b >= 1_000_000_000L -> String.format("%.2f %s", b / 1e9, if (uk) "ГБ" else "GB")
    b >= 1_000_000L -> String.format("%.1f %s", b / 1e6, if (uk) "МБ" else "MB")
    b >= 1_000L -> String.format("%d %s", b / 1000, if (uk) "КБ" else "KB")
    else -> "$b ${if (uk) "Б" else "B"}"
}

private fun fmtSpeed(bps: Double, uk: Boolean): String =
    if (bps >= 1e6) String.format("%.1f %s", bps / 1e6, if (uk) "МБ/с" else "MB/s")
    else String.format("%d %s", (bps / 1000).toInt().coerceAtLeast(1), if (uk) "КБ/с" else "KB/s")

@Composable
fun AppRowItem(r: AppRow, uk: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(10.dp)
        if (r.icon != null) Image(r.icon, null, Modifier.size(40.dp).clip(shape))
        else Box(Modifier.size(40.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(r.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.active) {
                Text(fmtSpeed(r.speedBps, uk), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else if (r.recent) {
                Text(
                    if (uk) "щойно був активний" else "was just active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(fmtBytes(r.todayBytes, uk), fontWeight = FontWeight.Bold)
    }
}

/** Картка на головному екрані: топ-3. Натискання відкриває повний список. */
@Composable
fun ActiveAppsCard(rows: List<AppRow>, uk: Boolean, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(if (uk) "Активні додатки" else "Active apps", fontWeight = FontWeight.Bold)
            Text(
                if (uk) "Хто споживає інтернет зараз і скільки спожив за сьогодні:"
                else "Who is using data right now and how much they used today:",
                style = MaterialTheme.typography.bodySmall
            )
            val top = rows.take(3)
            if (top.isEmpty()) {
                Text(if (uk) "Збираю дані…" else "Collecting data…", Modifier.padding(top = 8.dp))
            }
            top.forEachIndexed { i, r ->
                AppRowItem(r, uk)
                if (i < top.lastIndex) HorizontalDivider()
            }
            Text(
                if (uk) "Натисніть, щоб відкрити весь список" else "Tap to open the full list",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Повний список. Порядок той самий: спершу ті, хто споживає зараз. */
@Composable
fun AppsScreen(prefs: Prefs, onBack: () -> Unit) {
    val uk = prefs.language == "uk"
    BackHandler(onBack = onBack)
    val rows by rememberAppRows()
    var limit by remember { mutableIntStateOf(prefs.appListSize) }
    var sizeText by remember { mutableStateOf(if (prefs.appListSize > 0) prefs.appListSize.toString() else "") }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            Text(
                if (uk) "Активні додатки" else "Active apps",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        OutlinedTextField(
            value = sizeText,
            onValueChange = { v ->
                val d = v.filter { it.isDigit() }.take(4)
                sizeText = d
                limit = d.toIntOrNull() ?: 0
                prefs.appListSize = limit
            },
            label = { Text(if (uk) "Скільки показувати (порожньо — усі)" else "How many to show (empty = all)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        Text(
            if (uk) "Спершу ті, хто споживає зараз, далі щойно активні, далі за обсягом за сьогодні."
            else "Currently active first, then recently active, then by today's usage.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val shown = if (limit > 0) rows.take(limit) else rows
        LazyColumn {
            items(shown, key = { it.uid }) { r ->
                AppRowItem(r, uk)
                HorizontalDivider()
            }
        }
    }
}
