package com.agoria.app.ui.office.world

import com.agoria.app.core.AgentPolicy
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.DiaryEntryEntity

/** One message an agent said to a coworker (the `say` action). */
class ChatLine(
    val id: String,
    val speakerId: String,
    val speakerName: String,
    val listenerId: String,
    val listenerName: String,
    val text: String,
    val at: Long,
)

/**
 * Agent-to-agent talk is stored as diary entries of kind "say" with the content "To <name>: <text>",
 * so no database change is needed and it shows up in the Diary screen too.
 * Newest first.
 */
fun parseChatter(diary: List<DiaryEntryEntity>, agents: List<AgentEntity>): List<ChatLine> =
    diary.filter { it.kind == "say" }
        .mapNotNull { e ->
            val m = AgentPolicy.SAY_REGEX.find(e.content.trim()) ?: return@mapNotNull null
            val speaker = agents.firstOrNull { it.id == e.agentId } ?: return@mapNotNull null
            val toName = m.groupValues[1].trim()
            val listener = agents.firstOrNull {
                it.id != speaker.id && it.name.equals(toName, ignoreCase = true)
            } ?: return@mapNotNull null
            ChatLine(
                id = "${e.agentId}-${e.createdAt}",
                speakerId = speaker.id,
                speakerName = speaker.name,
                listenerId = listener.id,
                listenerName = listener.name,
                text = m.groupValues[2].trim(),
                at = e.createdAt,
            )
        }
        .sortedByDescending { it.at }
