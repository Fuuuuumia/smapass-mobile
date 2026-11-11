package com.groupe.smapassmobile.ui.stat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.groupe.smapassmobile.data.DeviceRepository
import com.groupe.smapassmobile.data.MeasurementUploader

@Serializable
data class DeviceSpec(
    val imei: String?,
    val manufacturer: String,
    val model: String,
    val deviceName: String?,
    val soc: String?,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val totalStorageBytes: Long,
    val screenSizeInch: Float,
    val densityDpi: Int,
    val updatedAt: Long,
)

@Serializable
data class DeviceStatus(
    val osVersion: String,
    val sdk: Int,
    val batteryTemperature: Float?,
    val batteryCycleCount: Int?,
    val remainingChargeMah: Int?,
    val estimatedFullChargeMah: Int?,
    val thermalHeadroom: Float?,
    val bootCount: Int?,
    val hasAbnormalExit: Boolean?,
    val collectedAt: Long,
)

data class StatUiState(
    val spec: DeviceSpec? = null,
    val status: DeviceStatus? = null,
    val isLoading: Boolean = true,
)

class StatScreenViewModel(
    private val collector: DeviceInfoCollector,
    private val repository: DeviceRepository,
    private val uploader: MeasurementUploader,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatUiState())
    val uiState: StateFlow<StatUiState> = _uiState.asStateFlow()

    private var lastCollectedAt = 0L
    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            val savedImei = repository.imei.first()
            val cachedSpec = repository.spec.first()
            val cachedStatus = repository.status.first()
            lastCollectedAt = cachedStatus?.collectedAt ?: 0L
            _uiState.update {
                it.copy(
                    spec = cachedSpec,
                    status = cachedStatus,
                    isLoading = cachedStatus == null,
                )
            }

            val spec = withContext(Dispatchers.IO) { collector.collectSpec() }
                .copy(imei = savedImei)
            _uiState.update { it.copy(spec = spec) }
            repository.saveSpec(spec)

            uploader.uploadSpecIfChanged(spec)
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchStatus()
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun fetchStatus() {
        val status = withContext(Dispatchers.IO) { collector.collectStatus(lastCollectedAt) }
        lastCollectedAt = status.collectedAt
        _uiState.update { it.copy(status = status, isLoading = false) }
        repository.saveStatus(status)

        viewModelScope.launch { uploadStatus(status) }
    }

    private suspend fun uploadStatus(status: DeviceStatus) {
        val since = repository.lastStatusSentAtOnce() ?: 0L
        val abnormal = withContext(Dispatchers.IO) { collector.hasAbnormalExitSince(since) }
        uploader.uploadStatusIfDue(status.copy(hasAbnormalExit = abnormal))
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                StatScreenViewModel(
                    collector = DeviceInfoCollector(app),
                    repository = DeviceRepository(app),
                    uploader = MeasurementUploader(app),
                )
            }
        }
    }
}

