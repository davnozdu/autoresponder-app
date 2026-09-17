package com.davnozdu.autoresponder.respond

import com.davnozdu.autoresponder.store.HistItem

/** Character budget for history only; the business Markdown is never trimmed here. */
object ConversationContext {
    const val BUDGET = 16_000
    fun render(items: List<HistItem>, budget: Int = BUDGET): String {
        val chosen = ArrayDeque<String>()
        var remaining = budget
        for (m in items.asReversed()) {
            val role = if (m.direction != "out") "Client" else if (m.auto) "Assistant" else "Master (human owner)"
            val line = "$role [${m.channel}]: ${m.body}\n"
            if (line.length <= remaining) { chosen.addFirst(line); remaining -= line.length }
            else {
                // Preserve whole recent turns. Only a single oversized newest turn is shortened.
                if (chosen.isEmpty() && remaining > 80) chosen.addFirst(line.take(remaining - 40) + "\n[oversized message truncated]\n")
                break
            }
        }
        return chosen.joinToString("")
    }
}
