package com.aipaca.app.agent.memory

/**
 * Deterministic regex pre-filter to prevent the agent from persisting
 * transient errors, negative tool claims, or broken-state observations
 * as permanent memory or skills.
 *
 * Hermes Agent relies on prompt-only rules, but small on-device models
 * (3B-8B) don't follow negative instructions reliably. This hard filter
 * catches the most common poisoning patterns before any write.
 */
object AntiPoisoning {

    private val POISON_PATTERNS = listOf(
        Regex("(?i)(doesn't|does not|can't|cannot|isn't|is not)\\s+(work|function|support|respond)"),
        Regex("(?i)(broken|unavailable|missing|not installed|not found)"),
        Regex("(?i)error:\\s"),
        Regex("(?i)(failed to|unable to|could not|couldn't)"),
        Regex("(?i)(crash|crashed|crashing|segfault|SIGSEGV)"),
        Regex("(?i)(timeout|timed out|connection refused)")
    )

    /** Returns true if [text] matches any poisoning pattern and should be rejected. */
    fun isPoisoned(text: String): Boolean = POISON_PATTERNS.any { it.containsMatchIn(text) }
}
