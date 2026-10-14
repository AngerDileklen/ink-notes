package app.einknotes.notes.ai

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.Sheet
import app.einknotes.notes.ui.label

/** A long text shown a screen at a time in a sheet, with optional action buttons (no scrolling on e-ink). */
object PagedText {
    fun paginate(text: String, size: Int): List<String> {
        val pages = ArrayList<String>(); var cur = StringBuilder()
        for (para in text.split("\n")) {
            if (cur.length + para.length > size && cur.isNotEmpty()) { pages.add(cur.toString().trim()); cur = StringBuilder() }
            cur.append(para).append("\n")
        }
        if (cur.isNotBlank()) pages.add(cur.toString().trim())
        return pages.ifEmpty { listOf(text) }
    }

    fun show(act: Activity, sheet: Sheet, title: String, text: String, actions: List<Pair<String, () -> Unit>> = emptyList(), page: Int = 0) {
        val pages = paginate(text, 850)
        val p = page.coerceIn(0, pages.size - 1)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(act).apply { this.text = pages[p]; textSize = 21f; setTextColor(Color.BLACK); setLineSpacing(0f, 1.15f); setPadding(8, 8, 8, 16) })
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        if (pages.size > 1) {
            bar.addView(EButton(act, "left", null) { show(act, sheet, title, text, actions, p - 1) }.apply { enabledState = p > 0 }, LinearLayout.LayoutParams(140, Eink.BAR_H))
            bar.addView(act.label("${p + 1} / ${pages.size}", 20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(160, -2))
            bar.addView(EButton(act, "right", null) { show(act, sheet, title, text, actions, p + 1) }.apply { enabledState = p < pages.size - 1 }, LinearLayout.LayoutParams(140, Eink.BAR_H))
        }
        for ((t, f) in actions) bar.addView(EButton(act, null, t) { sheet.hide(); f() }.apply { textSize = 26f }, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 12 })
        box.addView(bar)
        sheet.show(title, box)
    }
}
