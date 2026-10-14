package app.einknotes.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/** One English meaning of a word (from WordNet). [pos] is "noun", "verb", "adjective" or "adverb". */
class Meaning(val pos: String, val gloss: String, val example: String)

/** Turkish <-> English translations: [words] are the plain translations, [senses] say which meaning each belongs to. */
class Translation(val words: List<String>, val senses: List<String>)

class DictResult(
    val query: String,
    /** The dictionary form that was found (for example "run" for "running"), if it differs from the query. */
    val englishLemma: String?,
    val meanings: List<Meaning>,
    val toTurkish: Translation?,
    val turkishHeadword: String?,
    val fromTurkish: Translation?
) {
    val hasEnglish get() = meanings.isNotEmpty() || toTurkish != null
}

/**
 * Offline English and Turkish dictionary: English meanings and examples from WordNet, English <-> Turkish translations
 * from WikDict. The data lives in assets/dict.db (made by scripts/build_dict.py) and is copied next to the app's data on
 * first use. Inflected forms are traced back to their dictionary form ([Morphology]).
 */
class Dictionary(private val ctx: Context) {
    private val db: SQLiteDatabase? by lazy { open() }

    val available get() = db != null

    private fun open(): SQLiteDatabase? = try {
        val dir = File(ctx.filesDir, "dict").also { it.mkdirs() }
        val f = File(dir, "dict.db")
        // copied again after every install or update (the data may change between versions)
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val marker = File(dir, "dict.version")
        if (!f.exists() || marker.takeIf { it.exists() }?.readText() != version) {
            val tmp = File(dir, "dict.db.tmp")
            ctx.assets.open("dict.db").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            marker.writeText(version)
        }
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
    } catch (_: Throwable) { null }

    private fun meaningsOf(word: String): List<Meaning> {
        val d = db ?: return emptyList()
        val out = ArrayList<Meaning>()
        d.rawQuery("SELECT s.pos, s.gloss, s.ex FROM lem l JOIN syn s ON s.id = l.syn WHERE l.word = ? ORDER BY l.pf DESC, s.pos, l.rank", arrayOf(word)).use { c ->
            while (c.moveToNext()) out.add(Meaning(posName(c.getString(0)), c.getString(1), c.getString(2) ?: ""))
        }
        return out
    }

    private fun translation(table: String, word: String): Translation? {
        val d = db ?: return null
        d.rawQuery("SELECT simple, senses FROM $table WHERE word = ?", arrayOf(word)).use { c ->
            if (!c.moveToFirst()) return null
            val words = c.getString(0).split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
            val senses = (c.getString(1) ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            return if (words.isEmpty()) null else Translation(words, senses)
        }
    }

    private fun exists(table: String, word: String): Boolean {
        val d = db ?: return false
        d.rawQuery("SELECT 1 FROM $table WHERE word = ? LIMIT 1", arrayOf(word)).use { return it.moveToFirst() }
    }

    /** Looks [raw] up as English and as Turkish. Returns null when neither dictionary knows it. */
    fun lookup(raw: String): DictResult? {
        val q = raw.trim().trim('"', '“', '”', '‘', '’', '\'', '(', ')', '[', ']', ',', '.', ';', ':', '!', '?', '…', '—', '–').trim()
        if (q.isEmpty() || db == null) return null
        // English
        val lower = q.lowercase(Locale.ENGLISH)
        var enLemma: String? = null
        var meanings: List<Meaning> = emptyList()
        var toTr: Translation? = null
        for (cand in Morphology.englishCandidates(lower)) {
            val m = meaningsOf(cand)
            val t = translation("en_tr", cand)
            if (m.isNotEmpty() || t != null) { enLemma = cand; meanings = m; toTr = t; break }
        }
        // Turkish
        val trLower = q.substringBefore('\'').substringBefore('’').lowercase(Locale("tr"))
        var trHead: String? = null
        var fromTr: Translation? = null
        for ((i, cand) in Morphology.turkishCandidates(trLower).withIndex()) {
            val t = translation("tr_en", cand.word)
            if (t != null) {
                // a short stem is only trusted when English has no entry of its own
                if (cand.word.length >= 4 || i == 0 || enLemma == null) { trHead = cand.word; fromTr = t }
                break
            }
        }
        if (enLemma == null && fromTr == null) return null
        return DictResult(q, enLemma?.takeIf { it != lower }, meanings, toTr, trHead, fromTr)
    }

    private fun posName(p: String) = when (p) { "n" -> "noun"; "v" -> "verb"; "a" -> "adjective"; "r" -> "adverb"; else -> p }
}

/** Pure word-form rules, kept free of Android so they can be unit tested. */
object Morphology {
    private val irregular = mapOf(
        "was" to "be", "were" to "be", "is" to "be", "are" to "be", "am" to "be", "been" to "be", "being" to "be",
        "had" to "have", "has" to "have", "did" to "do", "does" to "do", "done" to "do", "went" to "go", "gone" to "go", "goes" to "go",
        "said" to "say", "made" to "make", "took" to "take", "taken" to "take", "came" to "come", "saw" to "see", "seen" to "see",
        "knew" to "know", "known" to "know", "got" to "get", "gotten" to "get", "gave" to "give", "given" to "give", "found" to "find",
        "thought" to "think", "told" to "tell", "became" to "become", "left" to "leave", "felt" to "feel", "brought" to "bring",
        "began" to "begin", "begun" to "begin", "kept" to "keep", "held" to "hold", "wrote" to "write", "written" to "write",
        "stood" to "stand", "heard" to "hear", "let" to "let", "meant" to "mean", "met" to "meet", "ran" to "run", "paid" to "pay",
        "sat" to "sit", "spoke" to "speak", "spoken" to "speak", "lay" to "lie", "led" to "lead", "read" to "read", "grew" to "grow",
        "grown" to "grow", "lost" to "lose", "fell" to "fall", "fallen" to "fall", "sent" to "send", "built" to "build", "understood" to "understand",
        "drew" to "draw", "drawn" to "draw", "broke" to "break", "broken" to "break", "spent" to "spend", "cut" to "cut", "rose" to "rise",
        "risen" to "rise", "drove" to "drive", "driven" to "drive", "bought" to "buy", "wore" to "wear", "worn" to "wear", "chose" to "choose",
        "chosen" to "choose", "ate" to "eat", "eaten" to "eat", "sang" to "sing", "sung" to "sing", "swam" to "swim", "flew" to "fly", "flown" to "fly",
        "children" to "child", "men" to "man", "women" to "woman", "feet" to "foot", "teeth" to "tooth", "mice" to "mouse", "people" to "person",
        "geese" to "goose", "lives" to "life", "wives" to "wife", "knives" to "knife", "leaves" to "leaf", "better" to "good", "best" to "good",
        "worse" to "bad", "worst" to "bad", "more" to "much", "most" to "much", "less" to "little", "least" to "little",
        "analyses" to "analysis", "crises" to "crisis", "criteria" to "criterion", "phenomena" to "phenomenon", "data" to "datum"
    )

    /** Possible dictionary forms of an English word, most likely first. */
    fun englishCandidates(w: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(w)
        var base = w
        if (base.endsWith("'s") || base.endsWith("’s")) base = base.dropLast(2)
        else if (base.endsWith("s'") || base.endsWith("s’")) base = base.dropLast(1)
        out.add(base)
        irregular[base]?.let { out.add(it) }
        fun add(s: String) { if (s.length >= 2) out.add(s) }
        fun undouble(s: String) = if (s.length >= 3 && s[s.length - 1] == s[s.length - 2] && s.last() !in "aeiou") s.dropLast(1) else s
        when {
            base.endsWith("ies") -> { add(base.dropLast(3) + "y"); add(base.dropLast(1)) }
            base.endsWith("ves") -> { add(base.dropLast(3) + "f"); add(base.dropLast(3) + "fe"); add(base.dropLast(1)) }
            base.endsWith("sses") || base.endsWith("shes") || base.endsWith("ches") || base.endsWith("xes") || base.endsWith("zes") -> { add(base.dropLast(2)); add(base.dropLast(1)) }
            base.endsWith("es") -> { add(base.dropLast(1)); add(base.dropLast(2)) }
            base.endsWith("s") && !base.endsWith("ss") -> add(base.dropLast(1))
        }
        when {
            base.endsWith("ied") -> add(base.dropLast(3) + "y")
            base.endsWith("ed") -> { val s = base.dropLast(2); add(s); add(s + "e"); add(undouble(s)) }
        }
        when {
            base.endsWith("ying") -> add(base.dropLast(4) + "ie")
            base.endsWith("ing") -> { val s = base.dropLast(3); add(s); add(s + "e"); add(undouble(s)) }
        }
        when {
            base.endsWith("ier") -> add(base.dropLast(3) + "y")
            base.endsWith("iest") -> add(base.dropLast(4) + "y")
            base.endsWith("est") -> { val s = base.dropLast(3); add(s); add(s + "e"); add(undouble(s)) }
            base.endsWith("er") -> { val s = base.dropLast(2); add(s); add(s + "e"); add(undouble(s)) }
        }
        when {
            base.endsWith("ily") -> add(base.dropLast(3) + "y")
            base.endsWith("ly") -> add(base.dropLast(2))
        }
        if (base.endsWith("ness")) add(base.dropLast(4))
        if (base.endsWith("men")) add(base.dropLast(3) + "man")
        return out.toList()
    }

    class TrCandidate(val word: String, val stemLength: Int)

    private val front = "eiöü"
    private val back = "aıou"

    /**
     * Possible dictionary forms of a Turkish word: the word itself, then ever shorter beginnings of it (Turkish adds endings),
     * each also with softened last consonants restored (kitabı -> kitap) and as an infinitive (okuyor -> oku -> okumak).
     */
    fun turkishCandidates(w: String): List<TrCandidate> {
        val out = LinkedHashMap<String, TrCandidate>()
        fun add(word: String, len: Int) { if (word.isNotEmpty() && word !in out) out[word] = TrCandidate(word, len) }
        add(w, w.length)
        val minLen = if (w.length <= 3) w.length else 2
        for (len in w.length - 1 downTo minLen) {
            val p = w.substring(0, len)
            add(p, len)
            val hardened = when (p.last()) { 'b' -> p.dropLast(1) + "p"; 'c' -> p.dropLast(1) + "ç"; 'd' -> p.dropLast(1) + "t"; 'ğ' -> p.dropLast(1) + "k"; else -> null }
            hardened?.let { add(it, len) }
            val lastVowel = p.lastOrNull { it in front || it in back }
            if (lastVowel != null && len >= 2) {
                val suffix = if (lastVowel in back) "mak" else "mek"
                add(p + suffix, len)
                // a dropped narrow vowel before -yor / -ıyor (okuyor -> oku already covered); doubled consonant verbs (gitti -> git)
            }
        }
        return out.values.toList()
    }
}
