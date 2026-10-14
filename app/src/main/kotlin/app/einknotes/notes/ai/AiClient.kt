package app.einknotes.notes.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The AI services the person can connect with their own key. */
enum class Provider(val label: String, val defaultModel: String, val canHearAudio: Boolean, val keyHelp: String) {
    OPENROUTER("OpenRouter", "google/gemini-2.5-flash", false, "from openrouter.ai/keys"),
    GEMINI("Google Gemini", "gemini-2.5-flash", true, "from aistudio.google.com/apikey"),
    OPENAI("OpenAI", "gpt-4o-mini", true, "from platform.openai.com/api-keys"),
    ANTHROPIC("Anthropic", "claude-sonnet-5-5", false, "from console.anthropic.com");
}

class AiConfig(val provider: Provider, val key: String, val model: String) {
    val ready get() = key.isNotBlank() && model.isNotBlank()
}

class AiException(message: String) : Exception(message)

class Segment(val start: Float, val end: Float, val text: String)

/**
 * Talks to the chosen AI service over HTTPS with the person's own key. Nothing is sent unless a button asks for it.
 * Every call blocks, so call it from a worker thread. The key is only ever put in a request header.
 */
object AiClient {

    // ---- chat -----------------------------------------------------------------------------------------------------

    /** Sends [prompt] (and page pictures, PNG bytes) and returns the answer text. [json] asks for a JSON-only answer. */
    fun complete(cfg: AiConfig, system: String, prompt: String, images: List<ByteArray> = emptyList(), json: Boolean = false): String {
        if (!cfg.ready) throw AiException("Add your API key and model in Settings > AI first")
        return when (cfg.provider) {
            Provider.OPENROUTER -> openAiChat(cfg, "https://openrouter.ai/api/v1/chat/completions", system, prompt, images, json)
            Provider.OPENAI -> openAiChat(cfg, "https://api.openai.com/v1/chat/completions", system, prompt, images, json)
            Provider.GEMINI -> geminiGenerate(cfg, system, prompt, images, null, json)
            Provider.ANTHROPIC -> anthropic(cfg, system, prompt, images, json)
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun openAiChat(cfg: AiConfig, url: String, system: String, prompt: String, images: List<ByteArray>, json: Boolean): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        for (img in images) content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64," + b64(img))))
        val body = JSONObject().put("model", cfg.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", content)))
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        val headers = mutableMapOf("Authorization" to "Bearer ${cfg.key}", "Content-Type" to "application/json")
        if (cfg.provider == Provider.OPENROUTER) { headers["X-Title"] = "Ink Notes" }
        return parseOpenAi(post(url, headers, body.toString().toByteArray()))
    }

    private fun anthropic(cfg: AiConfig, system: String, prompt: String, images: List<ByteArray>, json: Boolean): String {
        val content = JSONArray()
        for (img in images) content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/png").put("data", b64(img))))
        content.put(JSONObject().put("type", "text").put("text", prompt + if (json) "\n\nReply with JSON only, no other text." else ""))
        val body = JSONObject().put("model", cfg.model).put("max_tokens", 4096).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val headers = mapOf("x-api-key" to cfg.key, "anthropic-version" to "2023-06-01", "Content-Type" to "application/json")
        return parseAnthropic(post("https://api.anthropic.com/v1/messages", headers, body.toString().toByteArray()))
    }

    private fun geminiGenerate(cfg: AiConfig, system: String, prompt: String, images: List<ByteArray>, audioFileUri: Pair<String, String>?, json: Boolean): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        for (img in images) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/png").put("data", b64(img))))
        audioFileUri?.let { (uri, mime) -> parts.put(JSONObject().put("fileData", JSONObject().put("mimeType", mime).put("fileUri", uri))) }
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:generateContent"
        return parseGemini(post(url, mapOf("x-goog-api-key" to cfg.key, "Content-Type" to "application/json"), body.toString().toByteArray(), 300_000))
    }

    // ---- audio ----------------------------------------------------------------------------------------------------

    /** Turns a recording into timed text. Only Gemini and OpenAI accept audio. */
    fun transcribe(cfg: AiConfig, audio: File, progress: (String) -> Unit): List<Segment> {
        if (!cfg.ready) throw AiException("Add your API key and model in Settings > AI first")
        return when (cfg.provider) {
            Provider.GEMINI -> {
                progress("Uploading the recording...")
                val (uri, mime) = geminiUpload(cfg, audio, "audio/mp4")
                progress("Listening to the recording...")
                val text = geminiGenerate(cfg, "You transcribe recordings accurately.",
                    "Transcribe this recording. Reply with a JSON array of segments, each {\"start\": seconds, \"end\": seconds, \"text\": \"...\"}, " +
                        "about one sentence per segment, in the language spoken. No other text.", emptyList(), uri to mime, true)
                parseSegments(text)
            }
            Provider.OPENAI -> {
                if (audio.length() > 25L * 1024 * 1024) throw AiException("OpenAI accepts recordings up to 25 MB; this one is ${audio.length() / 1048576} MB. Use Gemini for long recordings.")
                progress("Uploading and transcribing...")
                parseWhisper(openAiTranscribe(cfg, audio))
            }
            else -> throw AiException("${cfg.provider.label} cannot listen to audio here. Choose Google Gemini or OpenAI in Settings > AI.")
        }
    }

    private fun openAiTranscribe(cfg: AiConfig, audio: File): String {
        val boundary = "----ink${System.nanoTime()}"
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()) }
        field("model", "whisper-1"); field("response_format", "verbose_json")
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.name}\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
        out.write(audio.readBytes()); out.write("\r\n--$boundary--\r\n".toByteArray())
        return post("https://api.openai.com/v1/audio/transcriptions", mapOf("Authorization" to "Bearer ${cfg.key}", "Content-Type" to "multipart/form-data; boundary=$boundary"), out.toByteArray(), 600_000)
    }

    /** Gemini Files API (resumable upload), so long lectures fit. Returns (file uri, mime type). */
    private fun geminiUpload(cfg: AiConfig, file: File, mime: String): Pair<String, String> {
        val start = URL("https://generativelanguage.googleapis.com/upload/v1beta/files").openConnection() as HttpURLConnection
        val uploadUrl: String
        try {
            start.requestMethod = "POST"; start.doOutput = true; start.connectTimeout = 30_000; start.readTimeout = 60_000
            start.setRequestProperty("x-goog-api-key", cfg.key)
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable"); start.setRequestProperty("X-Goog-Upload-Command", "start")
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", file.length().toString())
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            start.setRequestProperty("Content-Type", "application/json")
            start.outputStream.use { it.write("{\"file\":{\"display_name\":\"${file.name}\"}}".toByteArray()) }
            if (start.responseCode !in 200..299) throw AiException(errorOf(start.responseCode, start.errorStream?.bufferedReader()?.readText() ?: ""))
            uploadUrl = start.getHeaderField("X-Goog-Upload-URL") ?: throw AiException("Gemini did not give an upload address")
        } finally { start.disconnect() }
        val up = URL(uploadUrl).openConnection() as HttpURLConnection
        val resp: String
        try {
            up.requestMethod = "POST"; up.doOutput = true; up.connectTimeout = 30_000; up.readTimeout = 600_000
            up.setFixedLengthStreamingMode(file.length())
            up.setRequestProperty("Content-Length", file.length().toString())
            up.setRequestProperty("X-Goog-Upload-Offset", "0"); up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            up.outputStream.use { o -> file.inputStream().use { it.copyTo(o) } }
            if (up.responseCode !in 200..299) throw AiException(errorOf(up.responseCode, up.errorStream?.bufferedReader()?.readText() ?: ""))
            resp = up.inputStream.bufferedReader().readText()
        } finally { up.disconnect() }
        val f = JSONObject(resp).getJSONObject("file")
        val name = f.getString("name"); val uri = f.getString("uri")
        // wait until Google has processed the file
        var state = f.optString("state", "ACTIVE")
        var tries = 0
        while (state == "PROCESSING" && tries++ < 60) {
            Thread.sleep(2000)
            val s = post("https://generativelanguage.googleapis.com/v1beta/$name", mapOf("x-goog-api-key" to cfg.key), null, 30_000, "GET")
            state = JSONObject(s).optString("state", "ACTIVE")
        }
        if (state == "FAILED") throw AiException("Gemini could not process the recording")
        return uri to mime
    }

    // ---- http -----------------------------------------------------------------------------------------------------

    private fun post(url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int = 120_000, method: String = "POST"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 30_000; c.readTimeout = timeoutMs
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) { c.doOutput = true; c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.bufferedReader().readText()
            throw AiException(errorOf(code, c.errorStream?.bufferedReader()?.readText() ?: ""))
        } catch (e: AiException) { throw e
        } catch (e: java.net.UnknownHostException) { throw AiException("No internet connection")
        } catch (e: java.net.SocketTimeoutException) { throw AiException("The AI service took too long to answer")
        } catch (e: Exception) { throw AiException("Could not reach the AI service: ${e.message}")
        } finally { c.disconnect() }
    }

    // ---- parsing (kept free of Android types so it can be tested) ---------------------------------------------------

    fun errorOf(code: Int, body: String): String {
        val detail = try {
            val j = JSONObject(body)
            val e = j.opt("error")
            when (e) { is JSONObject -> e.optString("message"); is String -> e; else -> j.optString("message") }
        } catch (_: Exception) { "" }
        val hint = when (code) { 401, 403 -> " (check your API key)"; 404 -> " (check the model name)"; 429 -> " (too many requests or no credit left)"; else -> "" }
        return "The AI service said: ${detail.ifBlank { "error $code" }}$hint"
    }

    fun parseOpenAi(body: String): String {
        val j = JSONObject(body)
        val msg = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw AiException("The answer was empty")
        val c = msg.opt("content")
        return when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: "" }
            else -> throw AiException("The answer was empty")
        }.trim().ifEmpty { throw AiException("The answer was empty") }
    }

    fun parseGemini(body: String): String {
        val j = JSONObject(body)
        val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null) {
            val block = j.optJSONObject("promptFeedback")?.optString("blockReason")
            throw AiException(if (!block.isNullOrEmpty()) "Gemini blocked this request ($block)" else "The answer was empty")
        }
        return (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text") ?: "" }.trim().ifEmpty { throw AiException("The answer was empty") }
    }

    fun parseAnthropic(body: String): String {
        val arr = JSONObject(body).optJSONArray("content") ?: throw AiException("The answer was empty")
        return (0 until arr.length()).joinToString("") { arr.optJSONObject(it)?.takeIf { o -> o.optString("type") == "text" }?.optString("text") ?: "" }.trim()
            .ifEmpty { throw AiException("The answer was empty") }
    }

    /** Removes ```json fences and anything around the outermost JSON value. */
    fun extractJson(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) t = t.substringAfter('\n').substringBeforeLast("```").trim()
        val a = t.indexOfFirst { it == '{' || it == '[' }
        val b = t.indexOfLast { it == '}' || it == ']' }
        return if (a >= 0 && b > a) t.substring(a, b + 1) else t
    }

    fun parseSegments(text: String): List<Segment> {
        val arr = try {
            val j = extractJson(text)
            if (j.trimStart().startsWith("[")) JSONArray(j) else JSONObject(j).optJSONArray("segments") ?: JSONArray()
        } catch (e: Exception) { throw AiException("The transcript was not in the expected form") }
        val out = ArrayList<Segment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("text").trim()
            if (t.isNotEmpty()) out.add(Segment(o.optDouble("start", 0.0).toFloat(), o.optDouble("end", 0.0).toFloat(), t))
        }
        if (out.isEmpty()) throw AiException("Nothing was heard in the recording")
        return out
    }

    fun parseWhisper(body: String): List<Segment> {
        val j = JSONObject(body)
        val segs = j.optJSONArray("segments")
        val out = ArrayList<Segment>()
        if (segs != null) for (i in 0 until segs.length()) {
            val o = segs.optJSONObject(i) ?: continue
            val t = o.optString("text").trim()
            if (t.isNotEmpty()) out.add(Segment(o.optDouble("start", 0.0).toFloat(), o.optDouble("end", 0.0).toFloat(), t))
        }
        if (out.isEmpty()) j.optString("text").trim().takeIf { it.isNotEmpty() }?.let { out.add(Segment(0f, 0f, it)) }
        if (out.isEmpty()) throw AiException("Nothing was heard in the recording")
        return out
    }
}
