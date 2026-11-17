package app.kikigaki.memo

import android.os.SystemClock
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Gemini Live API(WebSocket)にマイク音声を流し続け、話すそばから文字起こしを受け取る。
 * つながらない間の音声は少しだけ手元にため、つながり直したら続けて送る。
 * どのモデルでも始められなかった場合は [failed] を立て、呼び出し側が従来の方式に戻す。
 */
class LiveTranscriber(private val key: String, private val preferredModel: String) {

    /** 接続が確立し、音声を流せている */
    @Volatile var streaming = false
        private set
    /** 使えるモデルが見つからず、あきらめた */
    @Volatile var failed = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var model = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()

    private var ws: WebSocket? = null
    private var generation = 0
    private var candidates: List<String> = emptyList()
    private var index = 0
    private var everWorked = false
    private var setupDone = false
    private var closed = false
    private var attempt = 0
    @Volatile private var downSince = SystemClock.elapsedRealtime()

    private val backlog = ArrayDeque<ByteArray>()
    private var backlogBytes = 0

    private val committed = ArrayList<String>()
    private val pending = StringBuilder()
    @Volatile private var lastDeltaAt = 0L

    fun start() {
        exec.execute {
            val list = ArrayList<String>()
            if (preferredModel.isNotBlank()) list.add(preferredModel.removePrefix("models/"))
            list.addAll(discover())
            list.addAll(DEFAULT_MODELS)
            synchronized(lock) { candidates = list.distinct() }
            connect()
        }
    }

    /** 接続が切れてからの経過時間。つながっていれば 0 */
    fun downForMs(): Long = if (streaming) 0 else SystemClock.elapsedRealtime() - downSince

    /** 録音スレッドから呼ばれる。16kHz/16bit/モノラルの PCM */
    fun offer(pcm: ByteArray, n: Int) {
        val copy = pcm.copyOf(n)
        synchronized(lock) {
            if (closed) return
            if (streaming) {
                sendAudio(copy)
            } else {
                backlog.addLast(copy)
                backlogBytes += copy.size
                while (backlogBytes > MAX_BACKLOG_BYTES && backlog.isNotEmpty()) {
                    backlogBytes -= backlog.removeFirst().size
                }
            }
        }
    }

    /** ためてある未送信の音声を捨てる(その区間を別の方式で文字起こししたとき用) */
    fun dropBacklog() {
        synchronized(lock) {
            backlog.clear()
            backlogBytes = 0
        }
    }

    /** 確定した行を取り出す。しばらく続きが来ない書きかけも、ここで確定させる */
    fun takeCommitted(): List<String> {
        synchronized(lock) {
            if (pending.isNotBlank() && SystemClock.elapsedRealtime() - lastDeltaAt > IDLE_COMMIT_MS) commit()
            if (committed.isEmpty()) return emptyList()
            val out = ArrayList(committed)
            committed.clear()
            return out
        }
    }

    /** まだ確定していない書きかけの文 */
    fun pendingText(): String = synchronized(lock) { pending.toString().trim() }

    /** 録音終了時に呼ぶ。残りの文字起こしを少し待ってから閉じる(呼び出しはブロックする) */
    fun finish() {
        val wasStreaming = synchronized(lock) {
            if (streaming) runCatching { ws?.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}") }
            streaming
        }
        if (wasStreaming) {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val quietFor = now - maxOf(lastDeltaAt, start)
                if (now - start > FINISH_MAX_MS || quietFor > FINISH_QUIET_MS) break
                Thread.sleep(100)
            }
        }
        synchronized(lock) { commit() }
        close()
    }

    fun close() {
        val already = synchronized(lock) {
            val was = closed
            if (!was) {
                closed = true
                streaming = false
                generation++
                runCatching { ws?.close(1000, null) }
                ws = null
            }
            was
        }
        if (already) return
        exec.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    // ───────── 接続 ─────────

    private fun connect() {
        val name = synchronized(lock) {
            if (closed) {
                null
            } else if (index >= candidates.size) {
                failed = true
                null
            } else {
                setupDone = false
                generation++
                candidates[index].also { model = it }
            }
        } ?: return
        val gen = synchronized(lock) { generation }
        val request = Request.Builder().url("$ENDPOINT?key=$key").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put(
                    "setup",
                    JSONObject()
                        .put("model", "models/$name")
                        .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
                        .put("inputAudioTranscription", JSONObject())
                )
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, webSocket, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                down(gen, "$code $reason".trim(), network = false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null) {
                    down(gen, "HTTP ${response.code}", network = false)
                } else {
                    down(gen, t.message ?: t.javaClass.simpleName, network = true)
                }
            }
        })
        synchronized(lock) {
            if (closed || gen != generation) socket.cancel() else ws = socket
        }
    }

    private fun handle(gen: Int, socket: WebSocket, raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        synchronized(lock) {
            if (closed || gen != generation) return
            if (msg.has("setupComplete")) {
                setupDone = true
                everWorked = true
                attempt = 0
                lastError = null
                while (backlog.isNotEmpty()) sendAudio(backlog.removeFirst(), socket)
                backlogBytes = 0
                streaming = true
            }
            msg.optJSONObject("serverContent")?.let { content ->
                content.optJSONObject("inputTranscription")?.let { t ->
                    val text = t.optString("text")
                    if (text.isNotEmpty()) {
                        pending.append(text)
                        lastDeltaAt = SystemClock.elapsedRealtime()
                    }
                    val last = pending.trimEnd().lastOrNull()
                    if (t.optBoolean("finished") || (pending.length >= 30 && last != null && last in SENTENCE_END)) {
                        commit()
                    }
                }
                if (content.optBoolean("turnComplete")) commit()
            }
            if (msg.has("goAway")) {
                // サーバーが間もなく切ると予告してきた。こちらから閉じて、すぐつなぎ直す
                runCatching { socket.close(1000, null) }
            }
        }
    }

    private fun down(gen: Int, reason: String, network: Boolean) {
        val delayMs = synchronized(lock) {
            if (closed || gen != generation) {
                -1L
            } else {
                if (streaming) downSince = SystemClock.elapsedRealtime()
                streaming = false
                ws = null
                if (reason.isNotBlank() && reason != "1000") lastError = "$model: $reason"
                if (!setupDone && !everWorked && !network) {
                    // このモデルでは始められなかった。次の候補へ
                    index++
                    200L
                } else {
                    attempt++
                    min(10_000L, 500L * attempt)
                }
            }
        }
        if (delayMs < 0) return
        runCatching { exec.schedule(Runnable { connect() }, delayMs, TimeUnit.MILLISECONDS) }
    }

    private fun sendAudio(pcm: ByteArray, socket: WebSocket? = ws) {
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        runCatching {
            socket?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
        }
    }

    private fun commit() {
        val line = pending.toString().trim()
        pending.setLength(0)
        if (line.isNotEmpty()) committed.add(line)
    }

    /** このAPIキーで使える「リアルタイム文字起こし用」モデルを問い合わせる */
    private fun discover(): List<String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            .openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("x-goog-api-key", key)
            if (conn.responseCode !in 200..299) return emptyList()
            val models = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models")
                ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val name = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (name.contains("transcribe") && methods.contains("bidiGenerateContent")) out.add(name)
            }
            // プレビュー版より正式版を先に試す
            out.sortedBy { if (it.contains("preview")) 1 else 0 }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val DEFAULT_MODELS = listOf("gemini-3.5-transcribe-live", "gemini-3.5-transcribe-live-preview")
        /** つながらない間にためておく音声の上限(約20秒ぶん) */
        private const val MAX_BACKLOG_BYTES = AacRecorder.SAMPLE_RATE * 2 * 20
        private const val IDLE_COMMIT_MS = 2500L
        private const val FINISH_MAX_MS = 4000L
        private const val FINISH_QUIET_MS = 1500L
        private const val SENTENCE_END = "。？！?!"
    }
}
