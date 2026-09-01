package com.aipaca.app.agent.memory

/**
 * Deterministic pre-filter that stops the agent from persisting transient errors,
 * negative tool claims, or broken-state observations as permanent memory or skills.
 *
 * Hermes Agent relies on prompt-only rules; small on-device models (3B–8B) don't
 * follow negative instructions reliably, so this hard filter runs before every write.
 *
 * Two tiers, because a single broad regex list was both too narrow and too wide:
 *
 * - [HARD_PATTERNS] are unambiguous machine failure — rejected outright.
 * - [SOFT_PATTERNS] are phrases that read as failure but occur just as often in
 *   legitimate facts about a person ("Janik could not attend the review").
 *   They are only rejected when the entry also mentions something technical
 *   ([TECH_MARKERS]), which is what turns "not found" into a tool complaint.
 *
 * Both tiers cover German as well as English: the app's user writes German, and
 * an English-only filter let every German error message straight through.
 */
object AntiPoisoning {

    /** Unambiguous failure output — never worth remembering. */
    private val HARD_PATTERNS = listOf(
        // English
        Regex("""(?i)\berror:\s"""),
        Regex("""(?i)\b(segfault|sigsegv|stack ?trace|traceback|nullpointerexception|exit code \d+)\b"""),
        Regex("""(?i)\b(timed out|timeout|connection refused|connection reset)\b"""),
        Regex("""(?i)\b(crashed|crashing)\b"""),
        // German
        Regex("""(?i)\bfehler:\s"""),
        Regex("""(?i)\b(abgestürzt|absturz|zeitüberschreitung|verbindung abgelehnt|speicherzugriffsfehler)\b"""),
        Regex("""(?i)\bfunktioniert nicht\b"""),
        Regex("""(?i)\bschlug fehl\b""")
    )

    /** Failure-shaped phrasing that is only poison in a technical context. */
    private val SOFT_PATTERNS = listOf(
        // English
        Regex("""(?i)\b(doesn't|does not|can't|cannot|isn't|is not)\s+(work|working|function|respond|support|available)\b"""),
        Regex("""(?i)\b(broken|unavailable|not installed|not found|missing)\b"""),
        Regex("""(?i)\b(failed to|unable to|could not|couldn't)\b"""),
        // German
        Regex("""(?i)\b(fehlgeschlagen|nicht gefunden|nicht installiert|nicht verfügbar|nicht erreichbar|kaputt)\b"""),
        Regex("""(?i)\bgeht nicht\b""")
    )

    /** Words that mark the surrounding sentence as being about machinery, not a person. */
    private val TECH_MARKERS = Regex(
        """(?i)\b(tool|tools|api|apis|server|endpoint|command|befehl|cli|file|datei|path|pfad|""" +
        """build|compile|install|installation|package|paket|library|bibliothek|dependency|""" +
        """script|skript|module|modul|model|modell|port|url|http|https|json|token|request|""" +
        """response|service|dienst|daemon|process|prozess|plugin|sdk|node|npm|gradle|docker)\b"""
    )

    /**
     * True when [text] should be rejected before it reaches disk.
     *
     * @param requireTechContextForSoftMatches when false (the default) a soft match
     *        alone is not enough; set true only if a caller wants the stricter,
     *        older behaviour.
     */
    fun isPoisoned(text: String, requireTechContextForSoftMatches: Boolean = true): Boolean {
        if (HARD_PATTERNS.any { it.containsMatchIn(text) }) return true
        val softHit = SOFT_PATTERNS.any { it.containsMatchIn(text) }
        if (!softHit) return false
        return if (requireTechContextForSoftMatches) TECH_MARKERS.containsMatchIn(text) else true
    }
}
