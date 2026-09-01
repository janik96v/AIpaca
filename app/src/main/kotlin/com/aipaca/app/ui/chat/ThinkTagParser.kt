package com.aipaca.app.ui.chat

// ---- Think-tag stream parser ------------------------------------------------

data class ThinkParseResult(val content: String, val thinking: String)

class ThinkTagParser {
    private data class TagPair(val open: String, val close: String)
    private val tagPairs = listOf(
        TagPair("<think>", "</think>"),
        TagPair("<|channel>thought\n", "<channel|>")
    )

    private var insideThink = false
    private var activeClose: String? = null
    private var buffer = ""

    private fun couldBePartialTag(text: String, tag: String): Boolean {
        for (i in 1 until tag.length) {
            if (text.endsWith(tag.substring(0, i))) return true
        }
        return false
    }

    private fun couldBeAnyPartialOpen(text: String): Boolean {
        return tagPairs.any { couldBePartialTag(text, it.open) }
    }

    fun feed(token: String): ThinkParseResult {
        buffer += token
        val contentParts = StringBuilder()
        val thinkParts   = StringBuilder()

        while (buffer.isNotEmpty()) {
            if (insideThink) {
                val closeTag = activeClose ?: break
                val idx = buffer.indexOf(closeTag)
                if (idx >= 0) {
                    thinkParts.append(buffer.substring(0, idx))
                    buffer = buffer.substring(idx + closeTag.length)
                    insideThink = false
                    activeClose = null
                } else if (couldBePartialTag(buffer, closeTag)) {
                    break
                } else {
                    thinkParts.append(buffer)
                    buffer = ""
                }
            } else {
                var bestIdx = -1
                var bestPair: TagPair? = null
                for (pair in tagPairs) {
                    val idx = buffer.indexOf(pair.open)
                    if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                        bestIdx = idx
                        bestPair = pair
                    }
                }
                if (bestPair != null && bestIdx >= 0) {
                    contentParts.append(buffer.substring(0, bestIdx))
                    buffer = buffer.substring(bestIdx + bestPair.open.length)
                    insideThink = true
                    activeClose = bestPair.close
                } else if (couldBeAnyPartialOpen(buffer)) {
                    break
                } else {
                    contentParts.append(buffer)
                    buffer = ""
                }
            }
        }
        return ThinkParseResult(contentParts.toString(), thinkParts.toString())
    }
}
