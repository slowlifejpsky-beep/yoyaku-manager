package app.kikigaki.memo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * 録音と要約を受け持つ常駐(フォアグラウンド)サービス。
 * 画面を消しても、他のアプリを使っていても動き続ける。
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var recorder: AacRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedWall = 0L
    /** 仕上げ(残りの文字起こし・要約)を待たずに終える指示 */
    @Volatile private var skipRequested = false

    /** 話すそばから文字起こしする接続。使わない・使えないときは null */
    private var liveTranscriber: LiveTranscriber? = null
    /** いまの録音の文字起こし全文と、「要約に未反映の文字起こしがある」印 */
    private var transcript = ""
    private var dirty = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 録音の状態が変わるたびに、ホーム画面のウィジェットの表示を合わせる
        scope.launch {
            Live.state.map { it.phase }.distinctUntilChanged().collect {
                RecordWidget.updateAll(this@RecordingService)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> recorder?.stop()
            ACTION_SKIP -> skipRequested = true
            ACTION_PROCESS -> startProcessing(intent?.getStringExtra(EXTRA_ID))
        }
        if (job?.isActive != true) shutdown()
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (job?.isActive == true) return
        startedWall = System.currentTimeMillis()
        if (!goForeground(mic = true, text = "録音中", recording = true)) {
            Live.state.value = LiveState(error = "録音を開始できません。マイクの許可を確認してください")
            return
        }
        val session = Sessions.create(this)
        val rec = AacRecorder(session.audio)
        val apiKey = Prefs.apiKey(this)
        val lt = if (apiKey.isNotBlank() && Prefs.liveEnabled(this)) LiveTranscriber(apiKey, Prefs.liveModel(this)) else null
        if (lt != null) rec.onPcm = lt::offer
        try {
            rec.start()
        } catch (e: Exception) {
            lt?.close()
            session.delete()
            Live.state.value = LiveState(error = "録音を開始できません: ${e.message}")
            return
        }
        recorder = rec
        liveTranscriber = lt
        lt?.start()
        if (Prefs.saveLocation(this)) {
            // 録音を始めた場所を記録する(取れなくても録音には影響しない)
            scope.launch {
                val place = Places.current(this@RecordingService) ?: return@launch
                withContext(Dispatchers.IO) {
                    synchronized(session) { session.writeMeta(session.readMeta().copy(place = place)) }
                }
            }
        }
        transcript = ""
        dirty = false
        acquireWakeLock()
        Live.state.value = LiveState(
            phase = Phase.RECORDING,
            sessionId = session.id,
            status = if (Prefs.apiKey(this).isBlank()) "APIキー未設定のため、音声の保存だけ行っています" else recordingStatus(),
        )
        job = scope.launch {
            val ticker = launch {
                while (rec.isRunning) {
                    Live.update { it.copy(elapsedMs = rec.elapsedMs, level = rec.level) }
                    drainLive(session)
                    delay(150)
                }
            }
            runSession(session, rec)
            ticker.cancel()
            shutdown()
        }
    }

    private fun startProcessing(id: String?) {
        if (job?.isActive == true) return
        val session = id?.let { Sessions.get(this, it) } ?: return
        if (!goForeground(mic = false, text = "文字起こし・要約を作成中…", recording = false)) return
        acquireWakeLock()
        Live.state.value = LiveState(
            phase = Phase.FINISHING,
            sessionId = session.id,
            elapsedMs = session.readMeta().durationMs,
            transcript = session.readTranscript(),
            summary = session.readSummary(),
            status = "文字起こし・要約を作成中…",
        )
        job = scope.launch {
            runSession(session, null)
            shutdown()
        }
    }

    /**
     * 音声ファイルの未処理部分を約20秒ごとに切り出して Gemini に送り、
     * 文字起こしと要約を更新していく。録音が止まり、残りを処理し終えたら抜ける。
     */
    private suspend fun runSession(session: Session, rec: AacRecorder?) {
        var meta = session.readMeta()
        var processed = meta.processedBytes
        transcript = session.readTranscript()
        var summary = session.readSummary()
        dirty = false
        var failures = 0
        var lastChunkAt = SystemClock.elapsedRealtime()
        var lastMetaAt = 0L
        var lastSummaryAt = 0L
        var lastError: String? = null
        skipRequested = false

        fun saveMeta(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastMetaAt < 5000) return
            lastMetaAt = now
            meta = meta.copy(
                // 場所は別の処理があとから書き込むので、ファイル上の値を引き継ぐ
                place = meta.place ?: session.readMeta().place,
                durationMs = rec?.elapsedMs ?: meta.durationMs,
                processedBytes = processed,
                title = summary.title.ifBlank { meta.title },
            )
            synchronized(session) { session.writeMeta(meta) }
        }

        while (true) {
            val recording = rec?.isRunning == true
            if (!recording && Live.state.value.phase == Phase.RECORDING) {
                Live.update { it.copy(phase = Phase.FINISHING, level = 0f, status = "仕上げ中…") }
                updateNotification("要約を仕上げています…")
                liveTranscriber?.let { lt ->
                    // 最後まで流せていたなら、残りの文字が届くのを少し待って全区間を処理済みにする
                    val wasStreaming = lt.streaming
                    withContext(Dispatchers.IO) { lt.finish() }
                    drainLive(session)
                    liveTranscriber = null
                    if (wasStreaming) processed = session.audio.length()
                }
            }
            if (!recording && skipRequested) {
                lastError = "要約を待たずに終了しました。下のボタンからあとで作れます"
                break
            }
            val total = if (recording) rec!!.bytesWritten else session.audio.length()
            val lt = liveTranscriber
            // リアルタイム接続が生きている間は、そこまでの音声は文字起こし済みとして扱う
            if (recording && lt != null && lt.streaming) processed = total
            // 切れて間もない間はつなぎ直しを待つ。長引く・使えないときだけ従来の方式で補う
            val liveCovers = recording && lt != null && !lt.failed && lt.downForMs() < LIVE_GRACE_MS
            val pending = total - processed
            val key = Prefs.apiKey(this)
            val model = Prefs.model(this)
            saveMeta(false)

            if (key.isBlank()) {
                if (!recording) break
                delay(500)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            val due = now - lastChunkAt >= CHUNK_INTERVAL_MS
            if (pending > 0 && (due || !recording) && !liveCovers) {
                val chunk = withContext(Dispatchers.IO) {
                    readChunk(session, processed, min(pending, MAX_CHUNK_BYTES).toInt())
                }
                lastChunkAt = now
                if (chunk.size < MIN_CHUNK_BYTES) {
                    // 録音中はまだ短すぎるだけなので次回に回す。終了後の端数や壊れた末尾は捨てる
                    if (!recording) processed = total
                } else {
                    try {
                        val before = transcript
                        val text = skippable(recording) { Gemini.transcribe(key, model, chunk, before) }
                        processed += chunk.size
                        liveTranscriber?.dropBacklog()
                        failures = 0
                        lastError = null
                        if (text.isNotBlank()) {
                            withContext(Dispatchers.IO) { session.appendTranscript(text) }
                            transcript = if (transcript.isBlank()) text else transcript.trimEnd() + "\n" + text
                            dirty = true
                        }
                        saveMeta(true)
                        Live.update {
                            it.copy(transcript = transcript, error = null, status = if (recording) recordingStatus() else "仕上げ中…")
                        }
                    } catch (e: Exception) {
                        failures++
                        lastError = e.message ?: "通信エラー"
                        Live.update { it.copy(error = lastError) }
                        if (!recording && failures >= MAX_FINAL_RETRIES) break
                        // 録音は続けたまま、少し待って同じ所からやり直す(未処理分は次回まとめて送る)
                        delay(if (recording) min(30_000L, 3000L * failures) else 1500L)
                        continue
                    }
                }
            }

            val summaryDue = !recording || summary.isEmpty ||
                SystemClock.elapsedRealtime() - lastSummaryAt >= SUMMARY_INTERVAL_MS
            if (dirty && summaryDue) {
                try {
                    lastSummaryAt = SystemClock.elapsedRealtime()
                    val fullText = transcript
                    val previous = summary
                    summary = skippable(recording) { Gemini.summarize(key, model, fullText, previous) }
                    withContext(Dispatchers.IO) { session.writeSummary(summary) }
                    dirty = false
                    failures = 0
                    lastError = null
                    saveMeta(true)
                    Live.update { it.copy(summary = summary, error = null) }
                } catch (e: Exception) {
                    failures++
                    lastError = e.message ?: "通信エラー"
                    Live.update { it.copy(error = lastError) }
                    if (!recording && failures >= MAX_FINAL_RETRIES) break
                    delay(if (recording) min(30_000L, 3000L * failures) else 1500L)
                    continue
                }
            }

            if (!recording && processed >= total && !dirty) break
            delay(300)
        }

        saveMeta(true)
        if (rec?.error != null) lastError = "録音が途中で止まりました: ${rec.error}"

        if (Prefs.driveLinked(this) && Prefs.driveAuto(this) && session.audio.length() > 0) {
            Live.update { it.copy(status = "Googleドライブに保存中…") }
            updateNotification("Googleドライブに保存中…")
            val result = try {
                Drive.upload(this, session)
                "done"
            } catch (e: Exception) {
                e.message ?: "ドライブに保存できませんでした"
            }
            session.writeMeta(session.readMeta().copy(drive = result))
        }

        Live.state.value = LiveState(phase = Phase.IDLE, sessionId = session.id, error = lastError)
    }

    /**
     * 通信を別スレッドで行い、仕上げ中に「待たずに終了」が押されたら結果を待つのをやめる。
     * (通信そのものは途中で止められないので、裏で終わるのを放っておく)
     */
    private suspend fun <T> skippable(recording: Boolean, block: () -> T): T {
        val work = ioScope.async { block() }
        while (!work.isCompleted) {
            if (!recording && skipRequested) throw IllegalStateException("skipped")
            delay(150)
        }
        return work.await()
    }

    /** リアルタイム接続から届いた文字を、全文とファイルと画面に反映する */
    private suspend fun drainLive(session: Session) {
        val lt = liveTranscriber ?: return
        val lines = lt.takeCommitted()
        if (lines.isNotEmpty()) {
            val text = lines.joinToString("\n")
            transcript = if (transcript.isBlank()) text else transcript.trimEnd() + "\n" + text
            dirty = true
            withContext(Dispatchers.IO) { session.appendTranscript(text) }
        }
        val pendingText = lt.pendingText()
        val shown = if (pendingText.isEmpty()) transcript else (transcript.trimEnd() + "\n" + pendingText).trim()
        Live.update {
            if (it.phase == Phase.RECORDING) it.copy(transcript = shown, status = recordingStatus()) else it.copy(transcript = shown)
        }
    }

    private fun recordingStatus(): String {
        val lt = liveTranscriber
        return when {
            lt == null -> "聞き取り中…(約10秒ごとに文字起こし、約30秒ごとに要約を更新)"
            lt.streaming -> "リアルタイムで文字起こし中(${lt.model})"
            lt.failed -> "リアルタイム接続ができないため、約10秒ごとの方式で文字起こし中(${lt.lastError ?: "原因不明"})"
            else -> "リアルタイム接続中…" + (lt.lastError?.let { "($it)" } ?: "")
        }
    }

    private fun readChunk(session: Session, offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        RandomAccessFile(session.audio, "r").use { f ->
            f.seek(offset)
            while (read < length) {
                val n = f.read(buf, read, length - read)
                if (n <= 0) break
                read += n
            }
        }
        val aligned = AacRecorder.alignedLength(buf, read)
        return if (aligned == buf.size) buf else buf.copyOf(aligned)
    }

    private fun buildNotification(text: String, recording: Boolean): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "録音", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("ききがきメモ")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setUsesChronometer(true).setWhen(startedWall).setShowWhen(true)
                .addAction(0, "停止して保存", stop)
        }
        return builder.build()
    }

    private fun goForeground(mic: Boolean, text: String, recording: Boolean): Boolean {
        val type = when {
            Build.VERSION.SDK_INT < 30 -> 0
            mic -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text, recording), type)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 常駐の種類は変えず、通知の文言だけ差し替える */
    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, false))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kikigaki:recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun shutdown() {
        recorder = null
        job = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        liveTranscriber?.close()
        liveTranscriber = null
        recorder?.stop()
        scope.cancel()
        runCatching { wakeLock?.release() }
        if (Live.state.value.phase != Phase.IDLE) {
            Live.update { it.copy(phase = Phase.IDLE) }
        }
        RecordWidget.updateAll(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.kikigaki.memo.START"
        const val ACTION_STOP = "app.kikigaki.memo.STOP"
        const val ACTION_SKIP = "app.kikigaki.memo.SKIP"
        const val ACTION_PROCESS = "app.kikigaki.memo.PROCESS"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        private const val CHUNK_INTERVAL_MS = 10_000L
        /** 録音中の要約の作り直しは、回数を抑えるためこの間隔を空ける(文字起こしは毎回出す) */
        private const val SUMMARY_INTERVAL_MS = 30_000L
        /** 通信が切れて溜まった分は、最大で約2分ぶんずつまとめて送る */
        private const val MAX_CHUNK_BYTES = AacRecorder.APPROX_BYTES_PER_SEC * 120
        /** 約0.5秒未満は送らない */
        private const val MIN_CHUNK_BYTES = 2000
        /** リアルタイム接続が切れても、この時間まではつなぎ直しを待つ */
        private const val LIVE_GRACE_MS = 15_000L
        private const val MAX_FINAL_RETRIES = 3
    }
}
