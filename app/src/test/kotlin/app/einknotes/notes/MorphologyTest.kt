package app.einknotes.notes

import app.einknotes.reader.Morphology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphologyTest {
    private fun en(w: String) = Morphology.englishCandidates(w)
    private fun tr(w: String) = Morphology.turkishCandidates(w).map { it.word }

    @Test fun englishInflections() {
        assertTrue("run" in en("running")); assertTrue("run" in en("ran")); assertTrue("be" in en("were"))
        assertTrue("study" in en("studies")); assertTrue("study" in en("studied")); assertTrue("make" in en("making"))
        assertTrue("box" in en("boxes")); assertTrue("child" in en("children")); assertTrue("happy" in en("happier"))
        assertTrue("book" in en("book's")); assertTrue("quick" in en("quickly")); assertTrue("hope" in en("hoped"))
        assertEquals("walked", en("walked").first())              // the word itself is always tried first
    }

    @Test fun turkishEndingsAndSoftening() {
        assertTrue("kitap" in tr("kitabı")); assertTrue("kitap" in tr("kitaplar")); assertTrue("ev" in tr("evler"))
        assertTrue("okumak" in tr("okuyor").let { it }) // via oku + mak
        assertTrue("gelmek" in tr("geliyorum")); assertTrue("ağaç" in tr("ağacın")); assertTrue("çocuk" in tr("çocuklar"))
        assertEquals("kitap", tr("kitap").first())
    }
}
