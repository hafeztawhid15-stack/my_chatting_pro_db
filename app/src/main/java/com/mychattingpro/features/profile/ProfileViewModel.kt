package com.mychattingpro.features.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val displayName: String = "",
    val about: String = "",
    val phoneE164: String = "",
    val avatarMediaId: String? = null,
    val isEditMode: Boolean = false,
    val isInitialLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isLoading: Boolean = false,
    val isUploadingPhoto: Boolean = false,
    val savedSuccess: Boolean = false,
    val error: AppError? = null
)

class ProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val tokenStore: EncryptedTokenStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var savedSnapshotName: String = ""
    private var savedSnapshotAbout: String = ""

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            val cachedSession = runCatching { tokenStore.getSession() }.getOrNull()
            if (cachedSession != null) {
                savedSnapshotName = cachedSession.displayName
                savedSnapshotAbout = cachedSession.about
                _uiState.value = _uiState.value.copy(
                    displayName = cachedSession.displayName,
                    about = cachedSession.about,
                    phoneE164 = cachedSession.phoneE164,
                    avatarMediaId = cachedSession.avatarMediaId,
                    isInitialLoading = false,
                    loadFailed = false,
                    error = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isInitialLoading = true,
                    loadFailed = false,
                    error = null
                )
            }

            profileRepository.fetchMyProfile()
                .onSuccess { p ->
                    savedSnapshotName = p.displayName
                    savedSnapshotAbout = p.about
                    _uiState.value = _uiState.value.copy(
                        displayName = p.displayName,
                        about = p.about,
                        phoneE164 = p.phoneE164,
                        avatarMediaId = p.avatarMediaId,
                        isInitialLoading = false,
                        loadFailed = false,
                        error = null
                    )
                }
                .onFailure { err ->
                    val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isInitialLoading = false,
                        loadFailed = cachedSession == null && _uiState.value.phoneE164.isBlank(),
                        error = appErr
                    )
                }
        }
    }

    fun enterEditMode() {
        _uiState.value = _uiState.value.copy(isEditMode = true, error = null, savedSuccess = false)
    }

    fun cancelEditMode() {
        _uiState.value = _uiState.value.copy(
            isEditMode = false,
            displayName = savedSnapshotName,
            about = savedSnapshotAbout,
            error = null
        )
    }

    fun updateDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name, savedSuccess = false, error = null)
    }

    fun updateAbout(about: String) {
        _uiState.value = _uiState.value.copy(about = about, savedSuccess = false, error = null)
    }

    fun consumeSavedNotice() {
        _uiState.value = _uiState.value.copy(savedSuccess = false)
    }

    fun uploadNewAvatar(uri: Uri) {
        _uiState.value = _uiState.value.copy(isUploadingPhoto = true, error = null, savedSuccess = false)
        viewModelScope.launch {
            val res = profileRepository.uploadProfilePhoto(uri)
            res.onSuccess { attachment ->
                val updated = profileRepository.updateProfile(
                    displayName = _uiState.value.displayName.ifBlank { "MY CHATTING PRO" },
                    about = _uiState.value.about,
                    avatarMediaId = attachment.id
                )
                updated.onSuccess { p ->
                    savedSnapshotName = p.displayName
                    savedSnapshotAbout = p.about
                    _uiState.value = _uiState.value.copy(
                        avatarMediaId = p.avatarMediaId,
                        isUploadingPhoto = false,
                        savedSuccess = true
                    )
                }.onFailure { err ->
                    val appErr = (err as? AppException)?.appError ?: AppError.StorageFailure
                    _uiState.value = _uiState.value.copy(isUploadingPhoto = false, error = appErr)
                }
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.StorageFailure
                _uiState.value = _uiState.value.copy(isUploadingPhoto = false, error = appErr)
            }
        }
    }

    fun saveProfile() {
        val state = _uiState.value
        if (state.displayName.isBlank()) return
        _uiState.value = state.copy(isLoading = true, error = null, savedSuccess = false)
        viewModelScope.launch {
            profileRepository.updateProfile(
                displayName = state.displayName.trim(),
                about = state.about.trim(),
                avatarMediaId = state.avatarMediaId
            ).onSuccess { p ->
                savedSnapshotName = p.displayName
                savedSnapshotAbout = p.about
                _uiState.value = _uiState.value.copy(
                    displayName = p.displayName,
                    about = p.about,
                    isEditMode = false,
                    isLoading = false,
                    loadFailed = false,
                    savedSuccess = true
                )
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(isLoading = false, error = appErr)
            }
        }
    }
}
