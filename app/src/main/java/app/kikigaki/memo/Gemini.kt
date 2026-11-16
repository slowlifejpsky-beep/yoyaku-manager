package app.kikigaki.memo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class GeminiException(val code: Int, message: String) : IOException(message)

object Gemini {

    private const val TRANSCRIBE_PROMPT = """あなたは文字起こし担当です。添付の音声(会話や会議の一部)を、話された言語のまま正確に文字起こししてください。
- 出力は文字起こしの本文だけ。前置き・説明・タイムスタンプ・話者名の推測は書かない
- 「えー」「あのー」などの言いよどみは省く
- 話者が変わったと分かる所で改行する
- 無音・雑音だけ・聞き取れない場合は、何も書かず空のまま返す。推測で文章を作らない
- 下の「直前までの文字起こし」は参考。固有名詞の表記をそろえ、同じ内容は繰り返さず、この音声の分だけを書く"""

    private const val SUMMARY_PROMPT = """以下は進行中の会話・会議の文字起こしです(音声認識なので誤字を含みます)。現時点までの内容を日本語でまとめ、指定のJSONで返してください。
- title: 内容が分かる件名(20文字以内)
- overview: 全体の概要を2〜3文で
- points: 要点。重要な順に最大8個、1個は1文で簡潔に
- decisions: 決まったこと。まだ検討中のものは入れない
- todos: これからやること。担当者や期限が分かれば含める
- 文字起こしに無いことは書かない。該当が無い項目は空の配列にする
- 「前回の要約」がある場合はそれを土台に更新し、変わっていない項目の言い回しはむやみに変えない"""

    private const val MAX_TRANSCRIPT_CHARS = 40_000

    fun transcribe(key: String, model: String, audio: ByteArray, previous: String): String {
        val context = previous.takeLast(400).ifBlank { "(なし)" }
        val parts = JSONArray()
            .put(JSONObject().put("text", "$TRANSCRIBE_PROMPT\n\n直前までの文字起こし:\n$context"))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "audio/aac")
                        .put("data", Base64.encodeToString(audio, Base64.NO_WRAP))
                )
            )
        val text = call(key, model, parts, null).trim()
        // 「(無音)」「[音声なし]」のような注記だけが返ってきた場合は空として扱う
        if (text.length < 30 && Regex("^[(（\\[【].*[)）\\]】]$").matches(text)) return ""
        return text
    }

    fun summarize(key: String, model: String, transcript: String, previous: Summary): Summary {
        val body = StringBuilder(SUMMARY_PROMPT)
        if (!previous.isEmpty) body.append("\n\n前回の要約:\n").append(previous.toJson().toString())
        body.append("\n\n文字起こし:\n")
        if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            body.append("(長いため前半は省略。前半の内容は前回の要約に反映済み)\n")
            body.append(transcript.takeLast(MAX_TRANSCRIPT_CHARS))
        } else {
            body.append(transcript)
        }
        val parts = JSONArray().put(JSONObject().put("text", body.toString()))
        val raw = call(key, model, parts, summarySchema())
        val json = raw.substringAfter("```json", raw).substringBeforeLast("```", raw).trim()
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start < 0 || end <= start) throw GeminiException(0, "要約を読み取れませんでした")
        return try {
            Summary.fromJson(JSONObject(json.substring(start, end + 1)))
        } catch (e: Exception) {
            throw GeminiException(0, "要約を読み取れませんでした")
        }
    }

    private fun summarySchema(): JSONObject {
        fun str() = JSONObject().put("type", "STRING")
        fun arr() = JSONObject().put("type", "ARRAY").put("items", str())
        return JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("title", str())
                    .put("overview", str())
                    .put("points", arr())
                    .put("decisions", arr())
                    .put("todos", arr())
            )
            .put("required", JSONArray(listOf("title", "overview", "points", "decisions", "todos")))
    }

    private fun call(key: String, model: String, parts: JSONArray, schema: JSONObject?): String {
        val gen = JSONObject().put("temperature", 0.2)
        if (schema != null) gen.put("responseMimeType", "application/json").put("responseSchema", schema)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrDefault("")
                throw GeminiException(code, friendly(code, detail, model))
            }
            val candidates = JSONObject(text).optJSONArray("candidates") ?: return ""
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
            val outParts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until outParts.length()) {
                val p = outParts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought")) continue
                sb.append(p.optString("text"))
            }
            return sb.toString()
        } catch (e: UnknownHostException) {
            throw GeminiException(-1, "ネットにつながっていません。つながり次第、続きから処理します")
        } catch (e: SocketTimeoutException) {
            throw GeminiException(-1, "通信が混み合っています。もう一度試します")
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(code: Int, detail: String, model: String): String = when {
        code == 400 && detail.contains("API key", ignoreCase = true) ->
            "Gemini APIキーが正しくありません。設定を確認してください"
        code == 401 || code == 403 -> "Gemini APIキーが使えません。設定を確認してください"
        code == 404 -> "モデル「$model」が見つかりません。設定のモデル名を確認してください"
        code == 429 -> "Geminiの利用上限に達しました。少し待って続きから処理します"
        code >= 500 -> "Gemini側が混み合っています。もう一度試します"
        else -> "Geminiエラー($code) $detail".trim()
    }
}
