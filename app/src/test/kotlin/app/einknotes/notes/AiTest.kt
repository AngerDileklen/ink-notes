package app.einknotes.notes

import app.einknotes.notes.ai.AiClient
import app.einknotes.notes.ai.AiException
import app.einknotes.notes.ai.Card
import app.einknotes.notes.ai.QuizKind
import app.einknotes.notes.ai.StudySet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiTest {
    @Test fun parsesOpenAiAndOpenRouterAnswers() {
        assertEquals("Hello", AiClient.parseOpenAi("""{"choices":[{"message":{"role":"assistant","content":" Hello "}}]}"""))
        assertEquals("ab", AiClient.parseOpenAi("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"""))
    }

    @Test fun parsesGeminiAndAnthropicAnswers() {
        assertEquals("Hi there", AiClient.parseGemini("""{"candidates":[{"content":{"parts":[{"text":"Hi "},{"text":"there"}]}}]}"""))
        assertEquals("Answer", AiClient.parseAnthropic("""{"content":[{"type":"text","text":"Answer"}]}"""))
        try { AiClient.parseGemini("""{"promptFeedback":{"blockReason":"SAFETY"}}"""); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("SAFETY")) }
    }

    @Test fun errorMessagesAreFriendly() {
        assertTrue(AiClient.errorOf(401, """{"error":{"message":"Incorrect API key"}}""").contains("check your API key"))
        assertTrue(AiClient.errorOf(404, """{"error":{"message":"model not found"}}""").contains("model name"))
        assertTrue(AiClient.errorOf(500, "not json").contains("error 500"))
    }

    @Test fun extractsJsonFromFencesAndChatter() {
        assertEquals("""{"a":1}""", AiClient.extractJson("```json\n{\"a\":1}\n```"))
        assertEquals("""[1,2]""", AiClient.extractJson("Sure! Here you go: [1,2] enjoy"))
    }

    @Test fun parsesTranscripts() {
        val s = AiClient.parseSegments("""```json
            [{"start":0,"end":4.5,"text":"Welcome."},{"start":4.5,"end":9,"text":" Today we cover discounting. "}]```""")
        assertEquals(2, s.size); assertEquals(4.5f, s[1].start, 0.001f); assertEquals("Today we cover discounting.", s[1].text)
        val w = AiClient.parseWhisper("""{"text":"x","segments":[{"start":1.0,"end":2.0,"text":" Hi"}]}""")
        assertEquals("Hi", w[0].text)
    }

    @Test fun studyCardsFromAiKeepOnlyValidQuestions() {
        val set = StudySet.fromAi("""{"flashcards":[{"front":"NPV","back":"Sum of discounted cash flows"},{"front":"","back":"x"}],
          "quiz":[{"type":"multiple_choice","question":"Q1","choices":["a","b","c"],"answer":"b","explanation":"because"},
                  {"type":"multiple_choice","question":"bad","choices":["a","b"],"answer":"z"},
                  {"type":"true_false","question":"Q2","answer":"True"},
                  {"type":"fill_blank","question":"Q3 ____","answer":"zero"}]}""")
        assertEquals(1, set.cards.size)
        assertEquals(listOf(QuizKind.MULTIPLE_CHOICE, QuizKind.TRUE_FALSE, QuizKind.FILL_BLANK), set.quiz.map { it.kind })
        assertEquals(listOf("True", "False"), set.quiz[1].choices)
    }

    @Test fun studySetSurvivesSaveAndLoad() {
        val s = StudySet()
        s.cards.add(Card("front", "back", 2, 12345L))
        val back = StudySet.fromJson(s.toJson())
        assertEquals(2, back.cards[0].box); assertEquals(12345L, back.cards[0].due)
    }

    @Test fun schedulingFollowsTheRating() {
        val c = Card("f", "b", box = 1)
        val now = 1_000_000_000L
        assertEquals(0, StudySet.reschedule(c, 0, now).first)
        assertEquals(now, StudySet.reschedule(c, 0, now).second)
        assertEquals(1, StudySet.reschedule(c, 1, now).first)
        assertEquals(2, StudySet.reschedule(c, 2, now).first)
        assertEquals(3L * 24 * 3600 * 1000 + now, StudySet.reschedule(c, 2, now).second)
        assertEquals(3, StudySet.reschedule(c, 3, now).first)
        assertEquals(5, StudySet.reschedule(Card("f", "b", box = 5), 3, now).first)
    }
}
