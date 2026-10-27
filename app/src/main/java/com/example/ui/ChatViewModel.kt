package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.AssistantCategory
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val currentSessionId: Long? = null,
    val currentSession: ChatSessionEntity? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val isLoading: Boolean = false,
    val userApiKey: String = "",
    val selectedModel: String = ChatRepository.DEFAULT_MODEL,
    val selectedImageUri: Uri? = null,
    val selectedImageBase64: String? = null,
    val selectedImageMimeType: String? = null,
    val activeCategory: AssistantCategory = AssistantCategory.TRADING,
    val showApiKeyDialog: Boolean = false,
    val showSessionsDrawer: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = ChatRepository(application, database.chatDao())

    private val _uiState = MutableStateFlow(
        ChatUiState(
            userApiKey = repository.getSavedApiKey(),
            selectedModel = repository.getSelectedModel()
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Collect sessions
        viewModelScope.launch {
            repository.getSessions().collect { sessionList ->
                _uiState.update { state ->
                    val updatedCurrentSession = sessionList.find { it.id == state.currentSessionId }
                    state.copy(
                        sessions = sessionList,
                        currentSession = updatedCurrentSession
                    )
                }

                // If no session selected, select the first or create a default session
                if (_uiState.value.currentSessionId == null) {
                    if (sessionList.isNotEmpty()) {
                        selectSession(sessionList.first().id)
                    } else {
                        startNewSession(AssistantCategory.TRADING)
                    }
                }
            }
        }
    }

    fun selectCategory(category: AssistantCategory) {
        _uiState.update { it.copy(activeCategory = category) }
    }

    fun selectSession(sessionId: Long) {
        _uiState.update { it.copy(currentSessionId = sessionId, showSessionsDrawer = false) }
        viewModelScope.launch {
            repository.getMessagesForSession(sessionId).collect { messageList ->
                _uiState.update { it.copy(messages = messageList) }
            }
        }
    }

    fun startNewSession(category: AssistantCategory? = null) {
        val cat = category ?: _uiState.value.activeCategory
        viewModelScope.launch {
            val newId = repository.createNewSession(cat)
            _uiState.update {
                it.copy(
                    activeCategory = cat,
                    currentSessionId = newId,
                    showSessionsDrawer = false,
                    selectedImageUri = null,
                    selectedImageBase64 = null,
                    selectedImageMimeType = null
                )
            }
            selectSession(newId)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) {
                val remaining = _uiState.value.sessions.filter { it.id != sessionId }
                if (remaining.isNotEmpty()) {
                    selectSession(remaining.first().id)
                } else {
                    startNewSession(_uiState.value.activeCategory)
                }
            }
        }
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            val base64Pair = repository.convertUriToBase64(uri)
            if (base64Pair != null) {
                _uiState.update {
                    it.copy(
                        selectedImageUri = uri,
                        selectedImageBase64 = base64Pair.first,
                        selectedImageMimeType = base64Pair.second,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(errorMessage = "Image load nahi ho payi. Kripya doosri image try karein.")
                }
            }
        }
    }

    fun removeImage() {
        _uiState.update {
            it.copy(
                selectedImageUri = null,
                selectedImageBase64 = null,
                selectedImageMimeType = null
            )
        }
    }

    fun sendMessage(inputPrompt: String) {
        val currentState = _uiState.value
        val sessionId = currentState.currentSessionId ?: return
        val prompt = inputPrompt.trim()
        val imageUri = currentState.selectedImageUri
        val imageBase64 = currentState.selectedImageBase64
        val imageMimeType = currentState.selectedImageMimeType

        // Must have at least text or image
        if (prompt.isEmpty() && imageBase64 == null) return

        // Check if API key is missing
        val currentKey = repository.getSavedApiKey()
        if (currentKey.isBlank()) {
            _uiState.update {
                it.copy(
                    showApiKeyDialog = true,
                    errorMessage = "Gemini API key daalna zaroori hai. Yahan enter karein ya Secrets panel mein set karein."
                )
            }
            return
        }

        // Clear input attachment immediately
        _uiState.update {
            it.copy(
                isLoading = true,
                selectedImageUri = null,
                selectedImageBase64 = null,
                selectedImageMimeType = null,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                repository.sendMessage(
                    sessionId = sessionId,
                    userPrompt = prompt,
                    imageUri = imageUri,
                    imageBase64 = imageBase64,
                    imageMimeType = imageMimeType
                )
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                if (e.message == "API_KEY_MISSING") {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showApiKeyDialog = true,
                            errorMessage = "Gemini API key enter karein."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.localizedMessage ?: "Kuch gadbad hui, dobara try karein."
                        )
                    }
                }
            }
        }
    }

    fun saveApiKey(newKey: String) {
        repository.saveApiKey(newKey)
        _uiState.update {
            it.copy(
                userApiKey = repository.getSavedApiKey(),
                showApiKeyDialog = false,
                errorMessage = null,
                infoMessage = "API Key save ho gayi!"
            )
        }
    }

    fun saveModel(model: String) {
        repository.saveSelectedModel(model)
        _uiState.update { it.copy(selectedModel = model) }
    }

    fun toggleApiKeyDialog(show: Boolean) {
        _uiState.update { it.copy(showApiKeyDialog = show) }
    }

    fun toggleSessionsDrawer(show: Boolean) {
        _uiState.update { it.copy(showSessionsDrawer = show) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }
}
