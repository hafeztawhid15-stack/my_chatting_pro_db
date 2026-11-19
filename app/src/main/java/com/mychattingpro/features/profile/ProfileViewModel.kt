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

    init {
        viewModelScope.launch {
            tokenStore.getSession()?.let { s ->
                _uiState.value = _uiState.value.copy(
                    displayName = s.displayName,
                    about = s.about,
                    phoneE164 = s.phoneE164,
                    avatarMediaId = s.avatarMediaId
                )
            }
            profileRepository.fetchMyProfile().onSuccess { p ->
                _uiState.value = _uiState.value.copy(
                    displayName = p.displayName,
                    about = p.about,
                    phoneE164 = p.phoneE164,
                    avatarMediaId = p.avatarMediaId
                )
            }
        }
    }

    fun updateDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name, savedSuccess = false, error = null)
    }

    fun updateAbout(about: String) {
        _uiState.value = _uiState.value.copy(about = about, savedSuccess = false, error = null)
    }

    fun uploadNewAvatar(uri: Uri) {
        _uiState.value = _uiState.value.copy(isUploadingPhoto = true, error = null)
        viewModelScope.launch {
            val res = profileRepository.uploadProfilePhoto(uri)
            res.onSuccess { attachment ->
                val updated = profileRepository.updateProfile(
                    displayName = _uiState.value.displayName,
                    about = _uiState.value.about,
                    avatarMediaId = attachment.id
                )
                updated.onSuccess { p ->
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
                _uiState.value = _uiState.value.copy(
                    displayName = p.displayName,
                    about = p.about,
                    isLoading = false,
                    savedSuccess = true
                )
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(isLoading = false, error = appErr)
            }
        }
    }
}
