package com.example.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AssistantCategory(val label: String, val emoji: String, val defaultPrompt: String) {
    TRADING(
        label = "Trading Chart",
        emoji = "📈",
        defaultPrompt = "Is chart ka analysis karo. Trend, Support/Resistance, Pattern, Entry, SL, Target aur Risk:Reward batao."
    ),
    STUDY(
        label = "Study Help",
        emoji = "📚",
        defaultPrompt = "Is topic ya question ko practical aur honest tareeqe se samjhao."
    ),
    BUSINESS(
        label = "Business Idea",
        emoji = "💡",
        defaultPrompt = "Is business idea ka realistic aur honest analysis karo with real risks."
    ),
    GENERAL(
        label = "Honest Q&A",
        emoji = "⚡",
        defaultPrompt = "Poochho kuch bhi honest aur sachha mashwara."
    )
}

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: Long,
    val role: String, // "user" or "model"
    val text: String,
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
