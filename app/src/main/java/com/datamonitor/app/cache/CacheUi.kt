@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.datamonitor.app.cache

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// Сесія живе в процесі, а не в Activity
// ============================================================
object CacheSession {
    var open by mutableStateOf(false)

    /** true, поки відкритий системний вибір файлів — тоді onStop не скидає сесію. */
    @Volatile var pickerActive = false

    enum class Entry { NONE, OPENED, CAN_CREATE }

    fun lock() {
        open = false
        CacheStore.peek()?.lock()
    }

    suspend fun tryEnter(ctx: Context, text: String): Entry = withContext(Dispatchers.Default) {
        if (text.isEmpty()) return@withContext Entry.NONE
        val s = CacheStore.get(ctx)
        if (s.exists()) {
            if (s.unlock(text.toCharArray())) Entry.OPENED else Entry.NONE
        } else if (text.length >= 6) Entry.CAN_CREATE else Entry.NONE
    }

    suspend fun create(ctx: Context, pw: String) = withContext(Dispatchers.Default) {
        CacheStore.get(ctx).create(pw.toCharArray())
    }
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> "%.2f ГБ".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.1f МБ".format(b / 1048576.0)
    b >= 1024 -> "${b / 1024} КБ"
    else -> "$b Б"
}

// ============================================================
// Діалог першого налаштування пароля
// ============================================================
@Composable
fun CacheCreateDialog(onCreated: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Новий пароль") },
        text = {
            Column {
                OutlinedTextField(p1, { p1 = it }, singleLine = true, label = { Text("Пароль (від 6 символів)") })
                OutlinedTextField(p2, { p2 = it }, singleLine = true, label = { Text("Повторіть пароль") })
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    p1.length < 6 -> err = "Мінімум 6 символів"
                    p1 != p2 -> err = "Паролі не збігаються"
                    else -> scope.launch {
                        CacheSession.create(ctx, p1)
                        CacheSession.open = true
                        onCreated()
                    }
                }
            }) { Text("Створити") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Скасувати") } }
    )
}

// ============================================================
// Кореневий екран
// ============================================================
sealed class Screen {
    object Browse : Screen()
    object Settings : Screen()
    class Media(val ids: List<String>, val start: Int) : Screen()
    class Text(val id: String) : Screen()
}

private fun secTitle(k: String) = when (k) {
    SEC_PHOTOS -> "Фото"
    SEC_VIDEOS -> "Відео"
    else -> "Файли"
}

@Composable
fun JobBanner() {
    val prog = CacheJobs.progress.value
    if (prog != null) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(if (prog.total > 0) "Обробка: ${prog.done} / ${prog.total}" else "Обробка…")
            if (prog.total > 0) LinearProgressIndicator(progress = { prog.done.toFloat() / prog.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    CacheJobs.message.value?.let {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f))
            TextButton(onClick = { CacheJobs.message.value = null }) { Text("OK") }
        }
    }
}

@Composable
fun CacheRoot() {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val store = remember { CacheStore.get(ctx) }
    val rev = store.rev   // підписка на зміни індексу
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    var stack by remember { mutableStateOf(listOf<String>()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Browse) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showAdd by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmExtract by remember { mutableStateOf(false) }
    val curStack = rememberUpdatedState(stack)

    fun target(): Pair<String?, String?> {
        val s = curStack.value
        return if (s.isEmpty()) Pair(null, null) else Pair(s.first(), s.last())
    }

    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        CacheSession.pickerActive = false
        val (s, p) = target()
        CacheJobs.importDocs(ctx, uris, s, p)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        CacheSession.pickerActive = false
        if (uri != null) {
            val (s, p) = target()
            CacheJobs.importTree(ctx, uri, s, p)
        }
    }
    val pickExtract = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        CacheSession.pickerActive = false
        if (uri != null) {
            CacheJobs.extract(ctx, selected.toList(), uri)
            selected = emptySet()
        }
    }
    val delLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        CacheSession.pickerActive = false
    }
    val pend = CacheJobs.pendingDelete.value
    LaunchedEffect(pend) {
        if (pend.isNotEmpty()) {
            CacheJobs.pendingDelete.value = emptyList()
            try {
                val pi = MediaStore.createDeleteRequest(ctx.contentResolver, pend)
                CacheSession.pickerActive = true
                delLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                CacheSession.pickerActive = false
            }
        }
    }

    val goBack: () -> Unit = {
        when {
            screen !is Screen.Browse -> screen = Screen.Browse
            selected.isNotEmpty() -> selected = emptySet()
            stack.isNotEmpty() -> stack = stack.dropLast(1)
            else -> CacheSession.lock()
        }
    }
    BackHandler { goBack() }

    when (val sc = screen) {
        is Screen.Settings -> SettingsScreen(store) { screen = Screen.Browse }
        is Screen.Media -> MediaViewer(store, sc.ids, sc.start) { screen = Screen.Browse }
        is Screen.Text -> TextViewer(store, sc.id) { screen = Screen.Browse }
        is Screen.Browse -> {
            val title = when {
                selected.isNotEmpty() -> "Вибрано: ${selected.size}"
                stack.isEmpty() -> "Розділи"
                stack.size == 1 -> secTitle(stack[0])
                else -> store.find(stack.last())?.name ?: ""
            }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                        },
                        actions = {
                            if (selected.isNotEmpty()) {
                                IconButton(onClick = { confirmExtract = true }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }
                                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, null) }
                            } else {
                                IconButton(onClick = { screen = Screen.Settings }) { Icon(Icons.Default.Settings, null) }
                            }
                        }
                    )
                },
                floatingActionButton = {
                    if (selected.isEmpty()) FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null) }
                }
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    JobBanner()
                    if (stack.isEmpty()) {
                        MainMenu(store, rev) { stack = listOf(it) }
                    } else {
                        val list = remember(stack, rev) { store.children(stack.last()) }
                        if (list.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Порожньо") }
                        } else {
                            LazyVerticalGrid(
                                GridCells.Adaptive(104.dp),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(list, key = { it.id }) { n ->
                                    Tile(store, n, n.id in selected,
                                        onClick = {
                                            if (selected.isNotEmpty()) {
                                                selected = if (n.id in selected) selected - n.id else selected + n.id
                                            } else if (n.folder) {
                                                stack = stack + n.id
                                            } else if (n.isImage || n.isVideo) {
                                                val ids = list.filter { it.isImage || it.isVideo }.map { it.id }
                                                screen = Screen.Media(ids, ids.indexOf(n.id).coerceAtLeast(0))
                                            } else {
                                                screen = Screen.Text(n.id)
                                            }
                                        },
                                        onLong = { selected = selected + n.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Додати") },
            text = {
                Column {
                    TextButton(onClick = {
                        showAdd = false; CacheSession.pickerActive = true
                        pickDocs.launch(arrayOf("image/*", "video/*"))
                    }) { Text("Фото й відео") }
                    TextButton(onClick = {
                        showAdd = false; CacheSession.pickerActive = true
                        pickDocs.launch(arrayOf("*/*"))
                    }) { Text("Файли") }
                    TextButton(onClick = {
                        showAdd = false; CacheSession.pickerActive = true
                        pickTree.launch(null)
                    }) { Text("Папка з телефону") }
                    if (stack.isNotEmpty()) TextButton(onClick = { showAdd = false; newFolder = true }) { Text("Нова папка") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Закрити") } }
        )
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("Нова папка") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Назва") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && stack.isNotEmpty()) store.addFolder(stack.last(), stack.first(), name.trim())
                    newFolder = false
                }) { Text("Створити") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Скасувати") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити назавжди?") },
            text = { Text("Елементів: ${selected.size}. Відновити їх буде неможливо.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList()
                    selected = emptySet(); confirmDelete = false
                    scope.launch(Dispatchers.IO) { store.delete(ids) }
                }) { Text("Видалити") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } }
        )
    }
    if (confirmExtract) {
        AlertDialog(
            onDismissRequest = { confirmExtract = false },
            title = { Text("Винести на телефон?") },
            text = { Text("Обрані елементи (${selected.size}) буде збережено у вибрану папку й прибрано звідси.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExtract = false; CacheSession.pickerActive = true
                    pickExtract.launch(null)
                }) { Text("Обрати папку") }
            },
            dismissButton = { TextButton(onClick = { confirmExtract = false }) { Text("Скасувати") } }
        )
    }
}

@Composable
private fun MainMenu(store: CacheStore, rev: Int, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(SEC_PHOTOS to "📷", SEC_VIDEOS to "🎬", SEC_FILES to "📁").forEach { (k, icon) ->
            val count = remember(rev) { store.countIn(k) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { open(k) }.padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 32.sp)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(secTitle(k), style = MaterialTheme.typography.titleMedium)
                        Text("$count", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(store: CacheStore, n: Node, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        if (n.isImage || n.isVideo) {
            val bmp by produceState<ImageBitmap?>(null, n.id) { value = withContext(Dispatchers.IO) { store.thumb(n.id) } }
            val b = bmp
            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (n.isVideo) "🎬" else "🖼", Modifier.align(Alignment.Center), fontSize = 30.sp)
            if (n.isVideo) Text("▶", Modifier.align(Alignment.BottomStart).padding(6.dp), color = Color.White, fontSize = 16.sp)
        } else {
            Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (n.folder) "📁" else "📄", fontSize = 34.sp)
                Text(n.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            }
        }
        if (sel) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
            Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(6.dp), tint = Color.White)
        }
    }
}

// ============================================================
// Налаштування
// ============================================================
object CacheBackup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun run(ctx: Context, okText: String, work: (Context) -> Boolean) {
        if (CacheJobs.progress.value != null) {
            CacheJobs.message.value = "Зачекайте завершення поточної операції"; return
        }
        val app = ctx.applicationContext
        CacheKeepAlive.acquire(app)
        CacheJobs.progress.value = CacheJobs.Progress(0, 0)
        scope.launch {
            try {
                CacheJobs.message.value = if (work(app)) okText else "Невірний пароль або пошкоджений файл"
            } catch (e: Throwable) {
                CacheJobs.message.value = "Помилка: ${e.message}"
            } finally {
                CacheJobs.progress.value = null
                CacheKeepAlive.release(app)
            }
        }
    }

    fun backup(ctx: Context, pw: String, uri: Uri) = run(ctx, "Резервну копію збережено") { app ->
        val ok = app.contentResolver.openOutputStream(uri)?.use { CacheStore.get(app).writeBackup(pw.toCharArray(), it) } ?: false
        if (!ok) try { DocumentsContract.deleteDocument(app.contentResolver, uri) } catch (_: Exception) {}
        ok
    }

    fun restore(ctx: Context, pw: String, uri: Uri) = run(ctx, "Дані відновлено") { app ->
        app.contentResolver.openInputStream(uri)?.use { CacheStore.get(app).restoreBackup(pw.toCharArray(), it) } ?: false
    }
}

@Composable
fun SettingsScreen(store: CacheStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var newPw2 by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var askBackupPw by remember { mutableStateOf(false) }
    var backupPw by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePw by remember { mutableStateOf("") }
    var confirmDestroy by remember { mutableStateOf(false) }
    val busy = CacheJobs.progress.value != null

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        CacheSession.pickerActive = false
        if (uri != null) CacheBackup.backup(ctx, backupPw, uri)
        backupPw = ""
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        CacheSession.pickerActive = false
        if (uri != null) restoreUri = uri
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Налаштування") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            JobBanner()
            Text("Зайнято: ${fmtSize(store.totalBytes())}")

            Text("Змінити пароль", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(oldPw, { oldPw = it }, singleLine = true, label = { Text("Поточний пароль") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newPw, { newPw = it }, singleLine = true, label = { Text("Новий пароль (від 6 символів)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newPw2, { newPw2 = it }, singleLine = true, label = { Text("Повторіть новий пароль") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                when {
                    newPw.length < 6 -> status = "Новий пароль: мінімум 6 символів"
                    newPw != newPw2 -> status = "Паролі не збігаються"
                    else -> scope.launch {
                        val ok = withContext(Dispatchers.Default) { store.changePassword(oldPw.toCharArray(), newPw.toCharArray()) }
                        status = if (ok) "Пароль змінено" else "Поточний пароль невірний"
                        if (ok) { oldPw = ""; newPw = ""; newPw2 = "" }
                    }
                }
            }) { Text("Змінити") }
            status?.let { Text(it) }

            Text("Резервна копія", style = MaterialTheme.typography.titleMedium)
            Text("Один зашифрований файл. Відновити його можна лише з паролем, який діяв на момент копії.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { askBackupPw = true }) { Text("Створити") }
                Button(enabled = !busy, onClick = { confirmRestore = true }) { Text("Відновити") }
            }

            Text("Видалення", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = { confirmDestroy = true }
            ) { Text("Видалити все") }
        }
    }

    if (askBackupPw) {
        AlertDialog(
            onDismissRequest = { askBackupPw = false },
            title = { Text("Підтвердіть пароль") },
            text = { OutlinedTextField(backupPw, { backupPw = it }, singleLine = true, label = { Text("Поточний пароль") }) },
            confirmButton = {
                TextButton(onClick = {
                    askBackupPw = false; CacheSession.pickerActive = true
                    createDoc.launch("dm_data.bin")
                }) { Text("Далі") }
            },
            dismissButton = { TextButton(onClick = { askBackupPw = false; backupPw = "" }) { Text("Скасувати") } }
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Відновити з копії?") },
            text = { Text("Поточні дані будуть повністю замінені даними з копії.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false; CacheSession.pickerActive = true
                    openDoc.launch(arrayOf("*/*"))
                }) { Text("Обрати файл") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Скасувати") } }
        )
    }
    restoreUri?.let { u ->
        AlertDialog(
            onDismissRequest = { restoreUri = null; restorePw = "" },
            title = { Text("Пароль копії") },
            text = { OutlinedTextField(restorePw, { restorePw = it }, singleLine = true, label = { Text("Пароль") }) },
            confirmButton = {
                TextButton(onClick = {
                    CacheBackup.restore(ctx, restorePw, u)
                    restoreUri = null; restorePw = ""
                }) { Text("Відновити") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null; restorePw = "" }) { Text("Скасувати") } }
        )
    }
    if (confirmDestroy) {
        AlertDialog(
            onDismissRequest = { confirmDestroy = false },
            title = { Text("Видалити все?") },
            text = { Text("Усі дані та пароль будуть стерті назавжди. Без резервної копії їх не повернути.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDestroy = false
                    store.destroy()
                    CacheSession.open = false
                }) { Text("Видалити") }
            },
            dismissButton = { TextButton(onClick = { confirmDestroy = false }) { Text("Скасувати") } }
        )
    }
}

// ============================================================
// Перегляд фото / відео
// ============================================================
@Composable
fun MediaViewer(store: CacheStore, ids: List<String>, start: Int, onClose: () -> Unit) {
    var idx by remember { mutableIntStateOf(start.coerceIn(0, (ids.size - 1).coerceAtLeast(0))) }
    val id = ids[idx]
    val n = store.find(id)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(id) {
            if (n?.isVideo == true) VideoPlayer(store, id) else ImageViewer(store, id)
        }
        Row(Modifier.align(Alignment.TopStart).statusBarsPadding().fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(n?.name ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${idx + 1}/${ids.size}", color = Color.White, modifier = Modifier.padding(end = 12.dp))
        }
        if (ids.size > 1) {
            if (idx > 0) TextButton(onClick = { idx-- }, modifier = Modifier.align(Alignment.CenterStart)) { Text("‹", color = Color.White, fontSize = 40.sp) }
            if (idx < ids.size - 1) TextButton(onClick = { idx++ }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("›", color = Color.White, fontSize = 40.sp) }
        }
    }
}

@Composable
private fun ImageViewer(store: CacheStore, id: String) {
    val bmp by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            try {
                store.openReader(id)?.use { r ->
                    if (r.length > 80L * 1024 * 1024) null else ImageUtil.decode(r.readAll(), 2400)?.asImageBitmap()
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    off = if (scale == 1f) Offset.Zero else off + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f
                })
            },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b, null,
                Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y),
                contentScale = ContentScale.Fit
            )
        } else CircularProgressIndicator(color = Color.White)
    }
}

/** Джерело даних ExoPlayer, яке розшифровує файл на льоту, нічого не пишучи на диск. */
@androidx.annotation.OptIn(UnstableApi::class)
private class CacheDataSource(private val store: CacheStore) : BaseDataSource(false) {
    private var reader: CacheCrypto.Reader? = null
    private var cur: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        cur = dataSpec.uri
        transferInitializing(dataSpec)
        val r = store.openReader(dataSpec.uri.host ?: "") ?: throw java.io.IOException("no file")
        reader = r
        pos = dataSpec.position
        if (pos > r.length) throw java.io.IOException("range")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.length - pos
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader?.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: -1
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = cur

    override fun close() {
        reader?.close(); reader = null
        cur = null
        if (opened) { opened = false; transferEnded() }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(store: CacheStore, id: String) {
    val ctx = LocalContext.current
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    val player = remember(id) {
        ExoPlayer.Builder(ctx).build().apply {
            val f = DataSource.Factory { CacheDataSource(store) }
            setMediaSource(ProgressiveMediaSource.Factory(f).createMediaSource(MediaItem.fromUri("cache://$id")))
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.height > 0) ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var showCtl by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize().clickable { showCtl = !showCtl }, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
        )
        if (showCtl) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                    Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 24.sp)
                }
                Slider(
                    value = pos.toFloat().coerceAtMost(dur.toFloat().coerceAtLeast(1f)),
                    onValueChange = { pos = it.toLong(); player.seekTo(it.toLong()) },
                    valueRange = 0f..dur.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f)
                )
                Text("${fmtTime(pos)} / ${fmtTime(dur)}", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ============================================================
// Перегляд файлу як тексту
// ============================================================
private class TextState(val lines: List<String>, val isText: Boolean, val truncated: Boolean)

@Composable
fun TextViewer(store: CacheStore, id: String, onClose: () -> Unit) {
    val n = store.find(id)
    var state by remember { mutableStateOf<TextState?>(null) }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        state = withContext(Dispatchers.IO) {
            try {
                store.openReader(id)?.use { r ->
                    val limit = 1_048_576L
                    val b = r.readAll(limit)
                    val nul = b.take(8192).any { it == 0.toByte() }
                    val s = String(b, Charsets.UTF_8)
                    val bad = s.take(8192).count { it == '\uFFFD' }
                    TextState(s.lines(), !nul && bad <= 160, r.length > limit)
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(n?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
        )
    }) { pad ->
        val st = state
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (st == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (st.isText || accepted) {
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    if (st.truncated) item { Text("Показано перший 1 МБ", style = MaterialTheme.typography.bodySmall) }
                    items(st.lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
            } else {
                AlertDialog(
                    onDismissRequest = onClose,
                    title = { Text("Це не текстовий файл") },
                    text = { Text("Вміст буде показано як набір символів і може виглядати нечитабельно. Продовжити?") },
                    confirmButton = { TextButton(onClick = { accepted = true }) { Text("Продовжити") } },
                    dismissButton = { TextButton(onClick = onClose) { Text("Скасувати") } }
                )
            }
        }
    }
}
