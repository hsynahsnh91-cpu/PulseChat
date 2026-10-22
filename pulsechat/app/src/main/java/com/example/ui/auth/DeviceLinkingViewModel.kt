package com.example.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.repository.ApproverScanState
import com.example.data.repository.DeviceLinkRepository
import com.example.data.repository.LinkedDeviceRecord
import com.example.data.repository.PairingRequestUiState
import com.example.data.repository.PairingSessionPreview
import com.example.domain.PairingError
import com.example.domain.PairingException
import com.example.util.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceLinkingViewModel(
    private val repo: DeviceLinkRepository,
    val connectivity: ConnectivityMonitor,
) : ViewModel() {

    val linkedDevices: StateFlow<List<LinkedDeviceRecord>> = repo.linkedDevices
    val devicesLoading: StateFlow<Boolean> = repo.devicesLoading
    val devicesError: StateFlow<PairingError?> = repo.devicesError
    val requestState: StateFlow<PairingRequestUiState> = repo.requestState
    val approverState: StateFlow<ApproverScanState> = repo.approverState

    val currentDeviceName: String get() = repo.currentDeviceName()
    val currentFriendlyId: String get() = repo.currentFriendlyId

    private val _revokingDeviceId = MutableStateFlow<String?>(null)
    val revokingDeviceId: StateFlow<String?> = _revokingDeviceId.asStateFlow()

    private val _actionError = MutableStateFlow<PairingError?>(null)
    val actionError: StateFlow<PairingError?> = _actionError.asStateFlow()

    // ================================================================ Auth-screen (new device) pairing request

    fun startPairingRequest() {
        viewModelScope.launch { repo.startPairingSession() }
    }

    fun cancelPairingRequest() {
        repo.cancelPairingSession()
    }

    fun resetPairingRequest() {
        repo.resetRequestState()
    }

    // ================================================================ Signed-in linked devices management

    fun refreshDevices() {
        viewModelScope.launch { repo.refreshLinkedDevices() }
    }

    fun revokeDevice(deviceId: String) {
        if (_revokingDeviceId.value != null) return
        _revokingDeviceId.value = deviceId
        _actionError.value = null
        viewModelScope.launch {
            try {
                repo.revokeDevice(deviceId)
            } catch (e: PairingException) {
                _actionError.value = e.error
            } catch (_: Exception) {
                _actionError.value = PairingError.UNKNOWN
            } finally {
                _revokingDeviceId.value = null
            }
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }

    // ================================================================ Signed-in approver scan / manual code / approve / reject

    fun resetApprover() {
        repo.resetApproverState()
    }

    fun onQrScanned(rawQr: String) {
        viewModelScope.launch { repo.inspectScannedQr(rawQr) }
    }

    fun onSubmitManualCode(rawCode: String) {
        viewModelScope.launch { repo.inspectManualCode(rawCode) }
    }

    fun approvePairing(preview: PairingSessionPreview, verificationPassword: String? = null) {
        viewModelScope.launch { repo.approvePairing(preview, verificationPassword) }
    }

    fun rejectPairing(preview: PairingSessionPreview) {
        viewModelScope.launch { repo.rejectPairing(preview) }
    }

    companion object {
        @StringRes
        fun errorStringRes(error: PairingError): Int = when (error) {
            PairingError.INVALID_QR -> R.string.device_link_err_invalid_qr
            PairingError.UNSUPPORTED_VERSION -> R.string.device_link_err_unsupported_version
            PairingError.INVALID_MANUAL_CODE -> R.string.device_link_err_invalid_code
            PairingError.EXPIRED -> R.string.device_link_err_expired
            PairingError.ALREADY_CONSUMED -> R.string.device_link_err_already_used
            PairingError.REJECTED -> R.string.device_link_err_rejected
            PairingError.CANCELLED -> R.string.device_link_err_cancelled
            PairingError.UNAUTHORIZED -> R.string.device_link_err_unauthorized
            PairingError.DEVICE_MISMATCH -> R.string.device_link_err_device_mismatch
            PairingError.DEVICE_REVOKED -> R.string.device_link_err_device_revoked
            PairingError.INVALID_SECRET_PROOF -> R.string.device_link_err_unauthorized
            PairingError.ALREADY_SIGNED_IN_DIFFERENT_ACCOUNT -> R.string.device_link_err_already_signed_in
            PairingError.RATE_LIMITED -> R.string.device_link_err_rate_limited
            PairingError.NETWORK -> R.string.error_network
            PairingError.SESSION_NOT_FOUND -> R.string.device_link_err_not_found
            PairingError.INVALID_STATE -> R.string.device_link_err_invalid_state
            PairingError.CRYPTO_FAILURE -> R.string.device_link_err_crypto
            PairingError.BACKEND_UNAVAILABLE -> R.string.error_backend_not_ready
            PairingError.UNKNOWN -> R.string.error_unknown
        }
    }
}

class DeviceLinkingViewModelFactory(
    private val repo: DeviceLinkRepository,
    private val connectivity: ConnectivityMonitor,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DeviceLinkingViewModel(repo, connectivity) as T
}
