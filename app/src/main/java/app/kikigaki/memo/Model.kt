package app.kikigaki.memo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Summary(
    val title: String = "",
    val overview: String = "",
    val points: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val todos: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = overview.isBlank() && points.isEmpty() && decisions.isEmpty() && todos.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("overview", overview)
        .put("points", JSONArray(points))
        .put("decisions", JSONArray(decisions))
        .put("todos", JSONArray(todos))

    companion object {
        fun fromJson(o: JSONObject): Summary = Summary(
            title = o.optString("title").trim(),
            overview = o.optString("overview").trim(),
            points = list(o.optJSONArray("points")),
            decisions = list(o.optJSONArray("decisions")),
            todos = list(o.optJSONArray("todos")),
        )

        private fun list(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until a.length()) {
                val s = a.optString(i).trim()
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }
    }
}

data class Meta(
    val startedAt: Long,
    val durationMs: Long = 0,
    val processedBytes: Long = 0,
    val title: String = "",
    /** "" = 未保存, "done" = 保存済み, それ以外 = エラー内容 */
    val drive: String = "",
)

enum class Phase { IDLE, RECORDING, FINISHING }

data class LiveState(
    val phase: Phase = Phase.IDLE,
    val sessionId: String? = null,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val transcript: String = "",
    val summary: Summary = Summary(),
    val status: String = "",
    val error: String? = null,
)

/** 録音サービスと画面のあいだで共有する現在の状態 */
object Live {
    val state = MutableStateFlow(LiveState())
    fun update(f: (LiveState) -> LiveState) = state.update(f)
}

object Prefs {
    const val DEFAULT_MODEL = "gemini-flash-latest"
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("apiKey", "")!!.trim()
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("apiKey", v.trim()).apply()

    fun model(c: Context): String =
        sp(c).getString("model", "")!!.trim().ifEmpty { DEFAULT_MODEL }
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v.trim()).apply()

    fun liveEnabled(c: Context): Boolean = sp(c).getBoolean("liveEnabled", true)
    fun setLiveEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("liveEnabled", v).apply()

    fun liveModel(c: Context): String = sp(c).getString("liveModel", "")!!.trim()
    fun setLiveModel(c: Context, v: String) = sp(c).edit().putString("liveModel", v.trim()).apply()

    fun driveLinked(c: Context): Boolean = sp(c).getBoolean("driveLinked", false)
    fun setDriveLinked(c: Context, v: Boolean) = sp(c).edit().putBoolean("driveLinked", v).apply()

    fun driveAuto(c: Context): Boolean = sp(c).getBoolean("driveAuto", true)
    fun setDriveAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("driveAuto", v).apply()

    fun driveFolderId(c: Context): String = sp(c).getString("driveFolderId", "")!!
    fun setDriveFolderId(c: Context, v: String) = sp(c).edit().putString("driveFolderId", v).apply()
}

/** 1回の録音。フォルダ1つに 音声・文字起こし・要約 をまとめて持つ */
class Session(val dir: File) {
    val id: String get() = dir.name
    val audio = File(dir, "audio.aac")
    private val transcriptFile = File(dir, "transcript.txt")
    private val summaryFile = File(dir, "summary.json")
    private val metaFile = File(dir, "meta.json")

    fun readMeta(): Meta {
        val fallbackStart = runCatching { ID_FORMAT.parse(id)!!.time }.getOrDefault(dir.lastModified())
        val o = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
            ?: return Meta(
                startedAt = fallbackStart,
                durationMs = audio.length() * 1000 / AacRecorder.APPROX_BYTES_PER_SEC,
            )
        return Meta(
            startedAt = o.optLong("startedAt", fallbackStart),
            durationMs = o.optLong("durationMs"),
            processedBytes = o.optLong("processedBytes"),
            title = o.optString("title"),
            drive = o.optString("drive"),
        )
    }

    fun writeMeta(m: Meta) = writeAtomic(
        metaFile,
        JSONObject()
            .put("startedAt", m.startedAt)
            .put("durationMs", m.durationMs)
            .put("processedBytes", m.processedBytes)
            .put("title", m.title)
            .put("drive", m.drive)
            .toString(),
    )

    fun readTranscript(): String = runCatching { transcriptFile.readText() }.getOrDefault("")

    fun appendTranscript(text: String) = transcriptFile.appendText(text.trimEnd() + "\n")

    fun readSummary(): Summary =
        runCatching { Summary.fromJson(JSONObject(summaryFile.readText())) }.getOrDefault(Summary())

    fun writeSummary(s: Summary) = writeAtomic(summaryFile, s.toJson().toString())

    fun displayTitle(): String = readMeta().title.ifBlank { readSummary().title }.ifBlank { "無題の録音" }

    /** 共有・ドライブ用のファイル名の頭。例: 2026-11-16 1930 朝の打ち合わせ */
    fun baseName(): String {
        val m = readMeta()
        val date = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.JAPAN).format(Date(m.startedAt))
        val title = displayTitle().replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim()
        return "$date $title"
    }

    /** 要約と文字起こしを1つのテキストにまとめ、共有用ファイルとして書き出す */
    fun exportText(): File {
        val f = File(dir, "ききがきメモ_$id.txt")
        f.writeText(buildText())
        return f
    }

    fun buildText(): String {
        val m = readMeta()
        val s = readSummary()
        val sb = StringBuilder()
        sb.append(displayTitle()).append("\n")
        sb.append(formatDate(m.startedAt)).append("  /  ").append(formatDuration(m.durationMs)).append("\n\n")
        if (s.overview.isNotBlank()) sb.append(s.overview).append("\n\n")
        fun section(name: String, items: List<String>) {
            sb.append("■ ").append(name).append("\n")
            if (items.isEmpty()) sb.append("（なし）\n") else items.forEach { sb.append("・").append(it).append("\n") }
            sb.append("\n")
        }
        section("要点", s.points)
        section("決定事項", s.decisions)
        section("TODO", s.todos)
        sb.append("■ 文字起こし\n").append(readTranscript().ifBlank { "（なし）\n" })
        return sb.toString()
    }

    fun delete() {
        dir.deleteRecursively()
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        val ID_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

object Sessions {
    private fun root(c: Context) = File(c.filesDir, "sessions").apply { mkdirs() }

    fun create(c: Context): Session {
        val now = System.currentTimeMillis()
        val dir = File(root(c), Session.ID_FORMAT.format(Date(now)))
        dir.mkdirs()
        return Session(dir).also { it.writeMeta(Meta(startedAt = now)) }
    }

    fun list(c: Context): List<Session> =
        (root(c).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
            .map { Session(it) }

    fun get(c: Context, id: String): Session? =
        File(root(c), id).takeIf { it.isDirectory }?.let { Session(it) }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd(E) HH:mm", Locale.JAPAN).format(Date(ms))
