package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.BuildConfig
import com.example.data.api.GeminiApiService
import com.example.data.db.AssistantCategory
import com.example.data.db.ChatDao
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.model.ContentMessage
import com.example.data.model.ContentPart
import com.example.data.model.GeminiRequest
import com.example.data.model.GenerationConfig
import com.example.data.model.InlineBlob
import com.example.data.model.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ChatRepository(
    private val context: Context,
    private val chatDao: ChatDao,
    private val apiService: GeminiApiService = GeminiApiService.create()
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mera_ai_prefs", Context.MODE_PRIVATE)

    companion object {
        const val PREF_KEY_API_KEY = "custom_gemini_api_key"
        const val PREF_KEY_MODEL = "selected_gemini_model"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val FALLBACK_MODEL = "gemini-1.5-flash"

        const val SYSTEM_PROMPT = """Tum ek honest assistant ho (Hinglish mein jawab do).
Trading chart par: trend, support/resistance, pattern batao, phir entry zone, stop loss, target aur risk:reward do. Kabhi guarantee ya accuracy % mat do; hamesha batao ki ye probability hai, pakki baat nahi. Agar setup kamzor hai to saaf bolo "trade mat lo".
Study aur business idea par: sach aur practical raho, risk bhi batao, jahan pakka nahi ho wahan "pakka nahi pata" bolo.
Zaroori rules:
1. Honest, transparent aur direct jawab do.
2. Kabhi false hope ya 100% guarantee mat do.
3. Bullets aur bold text use karke readability badhao."""
    }

    fun getSessions(): Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>> =
        chatDao.getMessagesForSession(sessionId)

    fun getSavedApiKey(): String {
        val saved = prefs.getString(PREF_KEY_API_KEY, "") ?: ""
        if (saved.isNotBlank()) return saved.trim()
        val buildKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
        return if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") buildKey else ""
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(PREF_KEY_API_KEY, key.trim()).apply()
    }

    fun getSelectedModel(): String {
        return prefs.getString(PREF_KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun saveSelectedModel(model: String) {
        prefs.edit().putString(PREF_KEY_MODEL, model).apply()
    }

    suspend fun createNewSession(category: AssistantCategory, initialTitle: String? = null): Long {
        val title = initialTitle ?: "${category.emoji} ${category.label} Chat"
        val session = ChatSessionEntity(
            title = title,
            category = category.name,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return chatDao.insertSession(session)
    }

    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSessionById(sessionId)
    }

    suspend fun updateSessionTitle(sessionId: Long, newTitle: String) {
        val session = chatDao.getSessionById(sessionId)
        if (session != null) {
            chatDao.updateSession(session.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun sendMessage(
        sessionId: Long,
        userPrompt: String,
        imageUri: Uri? = null,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getSavedApiKey()
        if (apiKey.isBlank()) {
            throw IllegalStateException("API_KEY_MISSING")
        }

        // Save user message in DB
        val effectiveText = if (userPrompt.isNotBlank()) {
            userPrompt
        } else {
            "Is chart ka analysis karo"
        }

        chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                text = effectiveText,
                imageUri = imageUri?.toString(),
                timestamp = System.currentTimeMillis()
            )
        )

        // Update session's updatedAt and title if it's the first message
        val prevMessages = chatDao.getMessagesForSessionSync(sessionId)
        if (prevMessages.size <= 1) {
            val shortTitle = if (effectiveText.length > 28) effectiveText.take(25) + "..." else effectiveText
            updateSessionTitle(sessionId, shortTitle)
        } else {
            val session = chatDao.getSessionById(sessionId)
            if (session != null) {
                chatDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
            }
        }

        // Construct conversation contents
        val contents = mutableListOf<ContentMessage>()
        for (m in prevMessages) {
            val parts = mutableListOf<Part>()
            if (m.id == prevMessages.last().id && !imageBase64.isNullOrBlank()) {
                // Attach image to the current user prompt
                parts.add(
                    Part(
                        text = m.text,
                        inlineData = InlineBlob(
                            mimeType = imageMimeType ?: "image/jpeg",
                            data = imageBase64
                        )
                    )
                )
            } else {
                parts.add(Part(text = m.text))
            }
            contents.add(ContentMessage(role = m.role, parts = parts))
        }

        val request = GeminiRequest(
            systemInstruction = ContentPart(parts = listOf(Part(text = SYSTEM_PROMPT))),
            contents = contents,
            generationConfig = GenerationConfig(temperature = 0.3)
        )

        val model = getSelectedModel()
        val response = try {
            apiService.generateContent(model = model, apiKey = apiKey, request = request)
        } catch (e: Exception) {
            // If primary model failed with connection or network, try fallback model
            try {
                apiService.generateContent(model = FALLBACK_MODEL, apiKey = apiKey, request = request)
            } catch (fallbackError: Exception) {
                throw Exception("Network ya Server error: ${fallbackError.localizedMessage ?: "Connection failed"}")
            }
        }

        if (response.isSuccessful) {
            val body = response.body()
            val text = body?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!text.isNullOrBlank()) {
                // Save model response to DB
                chatDao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "model",
                        text = text,
                        imageUri = null,
                        timestamp = System.currentTimeMillis()
                    )
                )
                return@withContext text
            } else {
                throw Exception("Assistant ka response empty mila. Model ya prompt check karein.")
            }
        } else {
            val errorBody = response.errorBody()?.string() ?: ""
            if (response.code() == 400 || response.code() == 403) {
                throw Exception("API Key galat hai ya permissions nahi hain. Code ${response.code()}: $errorBody")
            } else if (response.code() == 429) {
                throw Exception("Quota exceed ho gaya hai (Too many requests). Thodi der baad try karein.")
            } else {
                throw Exception("Error ${response.code()}: $errorBody")
            }
        }
    }

    suspend fun convertUriToBase64(uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) return@withContext null

            // Scale down if image is huge to prevent OOM and reduce network payload
            val maxDimension = 1200
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val width: Int
                val height: Int
                if (ratio > 1) {
                    width = maxDimension
                    height = (maxDimension / ratio).toInt()
                } else {
                    height = maxDimension
                    width = (maxDimension * ratio).toInt()
                }
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            Pair(base64, "image/jpeg")
        } catch (e: Exception) {
            null
        }
    }
}
