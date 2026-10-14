package app.einknotes.notes.ai

import android.app.Activity
import android.content.Context
import android.os.Handler
import app.einknotes.notes.ui.Row
import app.einknotes.notes.ui.Sheet
import kotlin.concurrent.thread

/** Which AI service, key and model to use. One setting for the whole app (notebooks and the book reader). The key stays on this tablet. */
class AiSettings(
    private val act: Activity,
    private val sheet: Sheet,
    private val ui: Handler,
    private val say: (String) -> Unit,
    private val work: (String?) -> Unit
) {
    private val prefs = act.getSharedPreferences("ai", Context.MODE_PRIVATE)

    private fun provider(): Provider = Provider.values().firstOrNull { it.name == prefs.getString("provider", null) } ?: Provider.GEMINI
    private fun key(p: Provider) = prefs.getString("key_${p.name}", "") ?: ""
    private fun model(p: Provider) = prefs.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun config(): AiConfig { val p = provider(); return AiConfig(p, key(p), model(p)) }

    /** The config, or null (after opening the settings) when no key has been added yet. */
    fun needKey(): AiConfig? {
        val cfg = config()
        if (!cfg.ready) { say("Add your API key in AI service settings first"); show(); return null }
        return cfg
    }

    fun show() {
        val p = provider()
        val k = key(p)
        sheet.list("AI service", listOf(
            Row("Service", p.label) {
                sheet.choose("AI service", Provider.values().map { it.label to it }, p) { prefs.edit().putString("provider", it.name).apply(); show() }
            },
            Row("API key", if (k.isEmpty()) "Not set - get one ${p.keyHelp}" else "Set (ends in ${k.takeLast(4)})") {
                sheet.input("API key for ${p.label}", "", hint = "Paste or type the key", password = true, ok = "Save") { v ->
                    prefs.edit().putString("key_${p.name}", v.trim()).apply(); show()
                }
            },
            Row("Model", model(p)) {
                sheet.input("Model name", model(p), hint = p.defaultModel, ok = "Save") { v -> prefs.edit().putString("model_${p.name}", v.trim()).apply(); show() }
            },
            Row("Test the connection", "Sends one short message") { test() },
            Row("Remove the key", enabled = k.isNotEmpty()) { prefs.edit().remove("key_${p.name}").apply(); show() },
            Row("What is sent", "Only when you tap an AI action: the page picture and its text, or the recording. The key stays on this tablet.", enabled = false) { }
        ))
    }

    private fun test() {
        val cfg = config()
        if (!cfg.ready) { say("Add your API key first"); return }
        work("Testing ${cfg.provider.label}...")
        thread(name = "ai-test") {
            val r = try { "Connected: " + AiClient.complete(cfg, "Reply briefly.", "Reply with the single word OK.").take(40) } catch (e: AiException) { e.message ?: "Failed" }
            android.util.Log.d("InkDebug", "ai test (${cfg.provider.name}): $r")
            ui.post { work(null); say(r) }
        }
    }

    /** Debug builds: tries the connection with a made-up key, to see that the service is reached and answers with its key error. */
    fun debugProbe(providerName: String) {
        val p = Provider.values().firstOrNull { it.name == providerName } ?: return
        prefs.edit().putString("provider", p.name).putString("key_${p.name}", "invalid-test-key").apply()
        test()
        ui.postDelayed({ prefs.edit().remove("key_${p.name}").apply() }, 20000)
    }
}
