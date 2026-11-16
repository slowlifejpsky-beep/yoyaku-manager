package app.kikigaki.memo

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

private val RecordRed = Color(0xFFD64545)

/** 画面を離れても続けたい処理(手動のドライブ保存)用 */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** 手動ドライブ保存の進行状況。キーは録音ID */
private val driveProgress = mutableStateMapOf<String, String>()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF7FCFD8), secondary = Color(0xFFE0B98A))
            } else {
                lightColorScheme(
                    primary = Color(0xFF1F6F78),
                    secondary = Color(0xFF9A6B3A),
                    background = Color(0xFFFAF7F2),
                    surface = Color(0xFFFAF7F2),
                )
            }
            MaterialTheme(colorScheme = colors) { AppRoot() }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object LiveView : Screen
    data object Settings : Screen
    data class Detail(val id: String) : Screen
}

@Composable
private fun AppRoot() {
    val live by Live.state.collectAsStateWithLifecycle()
    var screen by remember {
        mutableStateOf<Screen>(if (Live.state.value.phase == Phase.RECORDING) Screen.LiveView else Screen.Home)
    }

    LaunchedEffect(live.phase) {
        val id = live.sessionId
        if (live.phase == Phase.RECORDING && screen == Screen.Home) {
            screen = Screen.LiveView
        } else if (live.phase == Phase.IDLE && screen == Screen.LiveView) {
            screen = if (id != null) Screen.Detail(id) else Screen.Home
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            live = live,
            onOpenLive = { screen = Screen.LiveView },
            onOpen = { screen = Screen.Detail(it) },
            onSettings = { screen = Screen.Settings },
        )
        Screen.LiveView -> LiveScreen(live = live, onBack = { screen = Screen.Home })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        is Screen.Detail -> DetailScreen(id = s.id, live = live, onBack = { screen = Screen.Home })
    }
}

// ───────────────────────── ホーム ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    live: LiveState,
    onOpenLive: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val busy = live.phase != Phase.IDLE
    val sessions = remember(live.phase, live.sessionId) { Sessions.list(ctx) }
    val hasKey = remember { Prefs.apiKey(ctx).isNotBlank() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) {
            startRecording(ctx)
        } else {
            Toast.makeText(ctx, "録音するにはマイクの許可が必要です", Toast.LENGTH_LONG).show()
        }
    }

    fun onRecord() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.all { hasPermission(ctx, it) }) startRecording(ctx)
        else permissionLauncher.launch(wanted.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ききがきメモ") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "設定") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (busy) {
                        Button(onClick = onOpenLive) {
                            Text(
                                if (live.phase == Phase.RECORDING) "● 録音中  ${formatDuration(live.elapsedMs)}  ─ 開く"
                                else "仕上げ中… ─ 開く"
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(148.dp)
                                .clip(CircleShape)
                                .background(RecordRed)
                                .clickable { onRecord() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("録音", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "押すとすぐ録音が始まります。画面を消しても止まりません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    live.error?.let {
                        if (!busy) {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!hasKey) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Gemini APIキーが未設定です", fontWeight = FontWeight.Bold)
                            Text(
                                "このままでも録音はできますが、文字起こしと要約は行われません。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onSettings) { Text("設定を開く") }
                        }
                    }
                }
            }
            if (sessions.isNotEmpty()) {
                item {
                    Text(
                        "これまでの記録",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(sessions, key = { it.id }) { s ->
                val meta = remember(s.id, live.phase) { s.readMeta() }
                val title = remember(s.id, live.phase) { s.displayTitle() }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(formatDate(meta.startedAt))
                                append("  ・  ")
                                append(formatDuration(meta.durationMs))
                                if (meta.drive == "done") append("  ・  ドライブ保存済み")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun hasPermission(ctx: Context, permission: String) =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

private fun startRecording(ctx: Context) {
    ContextCompat.startForegroundService(
        ctx, Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_START)
    )
}

// ───────────────────────── 録音中 ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveScreen(live: LiveState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val recording = live.phase == Phase.RECORDING

    // 録音中の画面を開いている間は、要約を見続けられるよう画面を消さない
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (recording) "録音中" else "仕上げ中") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = {
                        ctx.startService(
                            Intent(ctx, RecordingService::class.java).setAction(
                                if (recording) RecordingService.ACTION_STOP else RecordingService.ACTION_SKIP
                            )
                        )
                    },
                    colors = if (recording) {
                        ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(if (recording) "■ 停止して保存" else "待たずに終了(要約はあとで作れます)", fontSize = 16.sp)
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recording) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(RecordRed))
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDuration(live.elapsedMs),
                        fontSize = 34.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { live.level },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    live.error ?: live.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (live.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            ContentTabs(live.summary, live.transcript, follow = true, modifier = Modifier.weight(1f))
        }
    }
}

// ───────────────────────── 要約・文字起こしの表示 ─────────────────────────

@Composable
private fun ContentTabs(summary: Summary, transcript: String, follow: Boolean, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("要約") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("文字起こし") })
        }
        if (tab == 0) SummaryView(summary, follow) else TranscriptView(transcript, follow)
    }
}

@Composable
private fun SummaryView(summary: Summary, live: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (summary.isEmpty) {
            Text(
                if (live) "話し始めて15秒ほどで最初の要約が出て、その後は約30秒ごとに更新されます。" else "要約はまだありません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (summary.title.isNotBlank()) {
                        Text(summary.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (summary.overview.isNotBlank()) Text(summary.overview)
                    SummarySection("要点", summary.points)
                    SummarySection("決定事項", summary.decisions)
                    SummarySection("TODO", summary.todos)
                }
            }
        }
    }
}

@Composable
private fun SummarySection(name: String, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (items.isEmpty()) {
            Text("まだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            items.forEach { item ->
                Row {
                    Text("・")
                    Text(item)
                }
            }
        }
    }
}

@Composable
private fun TranscriptView(transcript: String, follow: Boolean) {
    val scroll = rememberScrollState()
    if (follow) {
        // 新しい発話が足されたら末尾へ送る
        LaunchedEffect(transcript, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
        if (transcript.isBlank()) {
            Text(
                if (follow) "話し始めて10〜15秒ほどで、ここに文字起こしが出ます。" else "文字起こしはまだありません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer { Text(transcript.trim(), lineHeight = 26.sp) }
        }
    }
}

// ───────────────────────── 記録の詳細 ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(id: String, live: LiveState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val session = remember(id) { Sessions.get(ctx, id) }
    if (session == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val working = live.sessionId == id && live.phase != Phase.IDLE
    val progress = driveProgress[id]
    val meta = remember(id, live.phase, progress) { session.readMeta() }
    val summary = if (working) live.summary else remember(id, live.phase) { session.readSummary() }
    val transcript = if (working) live.transcript else remember(id, live.phase) { session.readTranscript() }
    val unprocessed = remember(id, live.phase) { session.audio.length() - meta.processedBytes > 4000 }
    var shareMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        summary.title.ifBlank { meta.title }.ifBlank { "無題の録音" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { shareMenu = true }) {
                            Icon(Icons.Filled.Share, contentDescription = "共有")
                        }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("音声とテキストを共有") },
                                onClick = { shareMenu = false; share(ctx, session, audio = true, text = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("音声だけ共有") },
                                onClick = { shareMenu = false; share(ctx, session, audio = true, text = false) },
                            )
                            DropdownMenuItem(
                                text = { Text("テキストだけ共有") },
                                onClick = { shareMenu = false; share(ctx, session, audio = false, text = true) },
                            )
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = !working) {
                        Icon(Icons.Filled.Delete, contentDescription = "削除")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${formatDate(meta.startedAt)}  ・  ${formatDuration(meta.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(live.error ?: live.status, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    if (live.sessionId == id && live.error != null) {
                        Text(live.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (unprocessed && live.phase == Phase.IDLE) {
                        OutlinedButton(
                            onClick = {
                                if (Prefs.apiKey(ctx).isBlank()) {
                                    Toast.makeText(ctx, "先に設定でGemini APIキーを入れてください", Toast.LENGTH_LONG).show()
                                } else {
                                    ContextCompat.startForegroundService(
                                        ctx,
                                        Intent(ctx, RecordingService::class.java)
                                            .setAction(RecordingService.ACTION_PROCESS)
                                            .putExtra(RecordingService.EXTRA_ID, id),
                                    )
                                }
                            },
                        ) { Text("まだ処理していない音声を文字起こし・要約する") }
                    }
                    DriveRow(session, meta, progress)
                }
            }
            Spacer(Modifier.height(4.dp))
            ContentTabs(summary, transcript, follow = working, modifier = Modifier.weight(1f))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この記録を削除しますか?") },
            text = { Text("端末内の音声・文字起こし・要約が消えます。ドライブに保存済みのファイルは残ります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    session.delete()
                    onBack()
                }) { Text("削除する") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun DriveRow(session: Session, meta: Meta, progress: String?) {
    val ctx = LocalContext.current
    if (!Prefs.driveLinked(ctx)) return
    val uploading = progress == UPLOADING
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = !uploading,
            onClick = {
                val id = session.id
                val appCtx = ctx.applicationContext
                driveProgress[id] = UPLOADING
                appScope.launch {
                    val result = try {
                        Drive.upload(appCtx, session)
                        "done"
                    } catch (e: Exception) {
                        e.message ?: "ドライブに保存できませんでした"
                    }
                    if (session.dir.isDirectory) session.writeMeta(session.readMeta().copy(drive = result))
                    driveProgress[id] = result
                }
            },
        ) {
            Text(
                when {
                    uploading -> "ドライブに保存中…"
                    meta.drive == "done" -> "もう一度ドライブに保存"
                    else -> "Googleドライブに保存"
                }
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            uploading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            meta.drive == "done" -> Text("保存済み", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (!uploading && meta.drive.isNotEmpty() && meta.drive != "done") {
        Text(meta.drive, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private const val UPLOADING = "uploading"

private fun share(ctx: Context, session: Session, audio: Boolean, text: Boolean) {
    val files = ArrayList<File>()
    if (audio && session.audio.length() > 0) files.add(session.audio)
    if (text) files.add(session.exportText())
    if (files.isEmpty()) {
        Toast.makeText(ctx, "共有できるファイルがありません", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND)
            .setType(if (audio) "audio/aac" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    val clip = ClipData.newRawUri("ききがきメモ", uris[0])
    for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "共有"))
}

// ───────────────────────── 設定 ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    var model by remember { mutableStateOf(Prefs.model(ctx)) }
    var linked by remember { mutableStateOf(Prefs.driveLinked(ctx)) }
    var auto by remember { mutableStateOf(Prefs.driveAuto(ctx)) }
    var driveMessage by remember { mutableStateOf<String?>(null) }

    fun onLinked() {
        Prefs.setDriveLinked(ctx, true)
        linked = true
        driveMessage = null
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val auth = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(result.data)
            if (auth.accessToken != null) onLinked() else driveMessage = "連携が完了しませんでした"
        } catch (e: Exception) {
            driveMessage = "連携できませんでした。ドライブ連携の準備(パッケージ名とSHA-1の登録)を確認してください\n詳細: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("文字起こし・要約(Gemini)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; Prefs.setApiKey(ctx, it) },
                label = { Text("Gemini APIキー") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Google AI Studio (aistudio.google.com) の「Get API key」で作れます。キーはこの端末の中だけに保存されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; Prefs.setModel(ctx, it) },
                label = { Text("モデル名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "ふだんは「${Prefs.DEFAULT_MODEL}」のままで大丈夫です。空にすると自動でこの値に戻ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text("Googleドライブ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (linked) "連携済みです。「${Drive.FOLDER_NAME}」フォルダに音声と要約ドキュメントを保存します。"
                else "連携すると、録音を止めたあと「${Drive.FOLDER_NAME}」フォルダに音声と要約ドキュメントを自動で保存します。最初に一度だけGoogle Cloud側の準備が必要です。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (linked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("録音を止めたら自動で保存", modifier = Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it; Prefs.setDriveAuto(ctx, it) })
                }
                OutlinedButton(onClick = { Prefs.setDriveLinked(ctx, false); linked = false }) {
                    Text("連携をやめる")
                }
            } else {
                Button(onClick = {
                    driveMessage = null
                    scope.launch {
                        try {
                            val result = Identity.getAuthorizationClient(ctx).authorize(Drive.authRequest()).await()
                            val pending = result.pendingIntent
                            if (result.hasResolution() && pending != null) {
                                consentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                            } else {
                                onLinked()
                            }
                        } catch (e: Exception) {
                            driveMessage = "連携できませんでした。ドライブ連携の準備(パッケージ名とSHA-1の登録)を確認してください\n詳細: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }) { Text("Googleドライブと連携する") }
            }
            driveMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "準備で登録する値\nパッケージ名: ${ctx.packageName}\nSHA-1: $SIGNING_SHA1\n\nアプリのバージョン: " +
                    (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SIGNING_SHA1 = "E3:89:C4:6D:4E:29:E3:4B:87:00:0E:EE:90:51:C6:8B:75:73:8E:5F"
