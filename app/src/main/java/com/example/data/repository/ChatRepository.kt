package com.example.data.repository

import com.example.data.local.ChatDao
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun getSessionById(sessionId: Long): ChatSessionEntity? {
        return chatDao.getSessionById(sessionId)
    }

    suspend fun createSession(
        title: String,
        personaId: String,
        customSystemPrompt: String,
        modelId: String
    ): Long {
        val now = System.currentTimeMillis()
        return chatDao.insertSession(
            ChatSessionEntity(
                title = title,
                personaId = personaId,
                customSystemPrompt = customSystemPrompt,
                modelId = modelId,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updateSession(session: ChatSessionEntity) {
        chatDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSessionById(sessionId)
    }

    suspend fun addMessage(
        sessionId: Long,
        content: String,
        isUser: Boolean,
        tokensPerSec: Float? = null,
        ttftMs: Long? = null,
        modelUsed: String? = null
    ): Long {
        val messageId = chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                content = content,
                isUser = isUser,
                tokensPerSec = tokensPerSec,
                ttftMs = ttftMs,
                modelUsed = modelUsed,
                timestamp = System.currentTimeMillis()
            )
        )
        chatDao.getSessionById(sessionId)?.let { session ->
            val updatedTitle = if (isUser && session.title == "Nueva conversación") {
                content.take(36).let { if (content.length > 36) "$it…" else it }
            } else {
                session.title
            }
            chatDao.updateSession(
                session.copy(
                    title = updatedTitle,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        return messageId
    }

    suspend fun updateMessageContent(
        messageId: Long,
        sessionId: Long,
        content: String,
        isUser: Boolean,
        tokensPerSec: Float? = null,
        ttftMs: Long? = null,
        modelUsed: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        chatDao.updateMessage(
            ChatMessageEntity(
                id = messageId,
                sessionId = sessionId,
                content = content,
                isUser = isUser,
                tokensPerSec = tokensPerSec,
                ttftMs = ttftMs,
                modelUsed = modelUsed,
                timestamp = timestamp
            )
        )
    }

    suspend fun getRecentContextMessages(sessionId: Long, limit: Int): List<ChatMessageEntity> {
        return chatDao.getRecentMessagesForContext(sessionId, limit).reversed()
    }

    suspend fun clearSessionMessages(sessionId: Long) {
        chatDao.clearMessagesForSession(sessionId)
    }

    suspend fun truncateMessagesFrom(sessionId: Long, fromMessageId: Long) {
        chatDao.deleteMessagesFromId(sessionId, fromMessageId)
    }
}
