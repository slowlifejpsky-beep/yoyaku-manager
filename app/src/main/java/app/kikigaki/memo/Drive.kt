package app.kikigaki.memo

import android.content.Context
import android.text.TextUtils
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Googleドライブの「ききがきメモ」フォルダへ、音声ファイルと要約ドキュメントを保存する。
 * 権限は drive.file(このアプリが作ったファイルだけ)に絞っている。
 */
object Drive {
    const val FOLDER_NAME = "ききがきメモ"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val DOC_MIME = "application/vnd.google-apps.document"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"

    fun authRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
        .build()

    /** 画面を出さずにアクセストークンを取る。未連携(同意が必要)なら null */
    private suspend fun token(ctx: Context): String? {
        val result = Identity.getAuthorizationClient(ctx).authorize(authRequest()).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    /** 音声と要約ドキュメントを保存する。失敗時は理由つきの IOException */
    suspend fun upload(ctx: Context, session: Session) {
        val token = try {
            token(ctx)
        } catch (e: Exception) {
            throw IOException("Googleの認証に失敗しました。ドライブ連携の準備を確認してください(${e.message})")
        } ?: throw IOException("ドライブと連携されていません。設定から連携し直してください")

        withContext(Dispatchers.IO) {
            val folder = ensureFolder(ctx, token)
            val base = session.baseName()
            if (session.audio.length() > 0) {
                uploadBytes(token, "$base.aac", null, folder, "audio/aac", session.audio, null)
            }
            val html = buildHtml(session).toByteArray(Charsets.UTF_8)
            uploadBytes(token, "$base 要約", DOC_MIME, folder, "text/html", null, html)
        }
    }

    private fun ensureFolder(ctx: Context, token: String): String {
        val saved = Prefs.driveFolderId(ctx)
        if (saved.isNotEmpty()) {
            val r = request("GET", "$API/$saved?fields=id,trashed", token, null)
            if (r.code == 200 && !JSONObject(r.body).optBoolean("trashed")) return saved
        }
        val q = URLEncoder.encode("name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false", "UTF-8")
        val found = request("GET", "$API?q=$q&fields=files(id)&pageSize=1", token, null).ok()
        val files = JSONObject(found.body).optJSONArray("files")
        val id = if (files != null && files.length() > 0) {
            files.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME)
            JSONObject(request("POST", "$API?fields=id", token, meta.toString()).ok().body).getString("id")
        }
        Prefs.setDriveFolderId(ctx, id)
        return id
    }

    private fun uploadBytes(
        token: String, name: String, targetMime: String?, folder: String,
        contentType: String, file: File?, bytes: ByteArray?,
    ) {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder))
        if (targetMime != null) meta.put("mimeType", targetMime)
        val start = request("POST", UPLOAD, token, meta.toString(), mapOf("X-Upload-Content-Type" to contentType)).ok()
        val location = start.location ?: throw IOException("ドライブへの保存を開始できませんでした")

        val length = file?.length() ?: bytes!!.size.toLong()
        val conn = URL(location).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(length)
            conn.outputStream.use { out ->
                if (file != null) file.inputStream().use { it.copyTo(out) } else out.write(bytes!!)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException(driveError(code, err))
            }
            conn.inputStream.close()
        } finally {
            conn.disconnect()
        }
    }

    private class Resp(val code: Int, val body: String, val location: String?) {
        fun ok(): Resp {
            if (code !in 200..299) throw IOException(driveError(code, body))
            return this
        }
    }

    private fun request(
        method: String, url: String, token: String, json: String?,
        headers: Map<String, String> = emptyMap(),
    ): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (json != null) {
                val data = json.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setFixedLengthStreamingMode(data.size)
                conn.outputStream.use { it.write(data) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, body, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }

    private fun driveError(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault("")
        return when (code) {
            401 -> "ドライブの認証が切れました。設定から連携し直してください"
            403 -> "ドライブに保存できません。Google Drive APIが有効か確認してください($detail)"
            else -> "ドライブエラー($code) $detail".trim()
        }
    }

    private fun buildHtml(session: Session): String {
        val m = session.readMeta()
        val s = session.readSummary()
        fun esc(t: String) = TextUtils.htmlEncode(t)
        val sb = StringBuilder("<html><head><meta charset=\"utf-8\"></head><body>")
        sb.append("<h1>").append(esc(session.displayTitle())).append("</h1>")
        sb.append("<p>").append(esc(formatDate(m.startedAt))).append(" / 録音時間 ")
            .append(formatDuration(m.durationMs)).append("</p>")
        if (s.overview.isNotBlank()) sb.append("<p>").append(esc(s.overview)).append("</p>")
        fun section(name: String, items: List<String>) {
            sb.append("<h2>").append(name).append("</h2>")
            if (items.isEmpty()) {
                sb.append("<p>(なし)</p>")
            } else {
                sb.append("<ul>")
                items.forEach { sb.append("<li>").append(esc(it)).append("</li>") }
                sb.append("</ul>")
            }
        }
        section("要点", s.points)
        section("決定事項", s.decisions)
        section("TODO", s.todos)
        sb.append("<h2>文字起こし</h2>")
        val transcript = session.readTranscript()
        if (transcript.isBlank()) sb.append("<p>(なし)</p>")
        transcript.lines().filter { it.isNotBlank() }.forEach { sb.append("<p>").append(esc(it)).append("</p>") }
        sb.append("</body></html>")
        return sb.toString()
    }
}
