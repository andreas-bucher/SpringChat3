package ch.arcticsoft.springchat3.tools

/**
 * Does this message ask for the PREVIOUS REPLY to be saved as a document?
 *
 * Shared on purpose between [WordDocumentEditTool.saveAnswerAsWordDocument]
 * and the prompt hint in `ChatAgent.documentEdit` that advertises it, so the
 * tool's own rule and the hint can never drift apart - the same reasoning
 * behind `documentSearchStrategyStepName` being a function rather than a
 * constant.
 *
 * 2026-08-29, from a real failure: "edit greeks.docx. make the stories
 * longer, around 500 characters.", with that document selected, produced no
 * edit at all. The hint was offered on that turn - it was gated only on a
 * previous reply EXISTING, which is true of every turn after the first - and
 * the model took it, saving the prior turn's apology as a 13-paragraph
 * "Summary.docx" while greeks.docx went untouched. A tool that "takes only a
 * filename" will beat an edit path needing a read, paragraph numbers and one
 * call per paragraph whenever both are on the table.
 *
 * **The asymmetry is the opposite of `ChatAgent.looksLikeDocumentChange`'s.**
 * That filter OPENS a step, so it is deliberately generous and a false
 * negative costs only a skipped feature invocation. This one CLOSES a door:
 * a false positive is a document full of the wrong text plus an edit that
 * never happened, and a false negative is one rephrase. So it demands both
 * halves of the request - a saving verb AND something standing in for the
 * reply - and stays quiet otherwise.
 *
 * "keep" is a weaker verb than the rest and is treated as one. "keep the
 * stories short", "keep the formatting" and "keep it under a page" are
 * ordinary editing instructions, so it counts only next to an explicit noun
 * for the reply, never next to a bare pronoun. The tool's own description
 * still offers the word to the user, and "keep the answer as Notes.docx"
 * still works.
 *
 * What it deliberately cannot separate: "translate the document and save it"
 * has both halves and is not a request to save the reply. That is accepted
 * rather than solved - the alternative is an LLM call every turn just to
 * rule the feature out, which is exactly what these pre-filters exist to
 * avoid - and `answer`'s guidance now reports what was actually done rather
 * than what was asked for, so a wrong save is visible instead of silent.
 */
object SaveAnswerIntent {

    /**
     * German entries carry no word boundary, so "abspeichern" and
     * "festhalten" match as substrings; none of them begin with an umlaut,
     * which is what would make a leading boundary behave oddly against
     * Java's ASCII-only word characters.
     *
     * The last alternative is there because German separable verbs come
     * apart in exactly the imperative this feature is asked for in: "halte
     * das bitte fest" carries no contiguous "festhalt" to match. Bounded to
     * a few intervening words so it stays a verb match rather than a search
     * of the whole sentence. "speichere das ab" needs no such handling - the
     * stem is still contiguous there.
     */
    private val SAVE_VERB = Regex(
        "\\b(saves?|saved|saving|stores?|stored|storing|write\\s+down|write\\s+\\w+\\s+down)\\b" +
            "|speicher|festhalt|notier" +
            "|\\bhalt\\w*\\s+(\\w+\\s+){0,3}fest\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Counts only alongside [ANSWER_NOUN] - see this object's doc comment. */
    private val WEAK_SAVE_VERB = Regex("\\b(keeps?|keeping)\\b|behalt", RegexOption.IGNORE_CASE)

    private val ANSWER_NOUN = Regex(
        "\\b(answers?|repl(y|ies)|responses?|summar(y|ies))\\b" +
            "|antwort|zusammenfassung" +
            "|\\bwhat\\s+you\\s+(just\\s+)?(said|wrote|told)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Word-bounded for a reason: a plain `contains` check for "it" matches
     * "edit", which is the exact word the failing message opened with.
     */
    private val ANSWER_PRONOUN = Regex(
        "\\b(it|that|this|these|es|das|dies)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isAskedFor(message: String): Boolean {
        val noun = ANSWER_NOUN.containsMatchIn(message)
        return when {
            SAVE_VERB.containsMatchIn(message) -> noun || ANSWER_PRONOUN.containsMatchIn(message)
            WEAK_SAVE_VERB.containsMatchIn(message) -> noun
            else -> false
        }
    }
}
