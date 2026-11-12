package com.groupe.smapassmobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.groupe.smapassmobile.data.DeviceEnrollmentRepository
import com.groupe.smapassmobile.data.DeviceRepository
import com.groupe.smapassmobile.data.MeasurementUploader
import com.groupe.smapassmobile.data.remote.SmapassApi
import com.groupe.smapassmobile.data.remote.Visibility
import com.groupe.smapassmobile.ui.stat.DeviceInfoCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val imei: String? = null,
    val deviceId: String? = null,
    val isRegistered: Boolean = false,
    /** サーバー上の実際の公開状態（lookup の visibility）。未取得なら null。 */
    val visibility: String? = null,
    val lastUploadResult: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
)

private data class LocalState(
    val visibility: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val repository: DeviceRepository,
    private val enrollment: DeviceEnrollmentRepository,
    private val uploader: MeasurementUploader,
    private val collector: DeviceInfoCollector,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    val uiState: StateFlow<SettingsUiState> =
        combine(
            repository.imei,
            repository.deviceId,
            repository.isRegistered,
            repository.lastUploadResult,
            local,
        ) { imei, deviceId, registered, lastUpload, state ->
            SettingsUiState(
                imei = imei,
                deviceId = deviceId,
                isRegistered = registered,
                visibility = if (registered) state.visibility else null,
                lastUploadResult = lastUpload,
                isBusy = state.isBusy,
                message = state.message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    init {
        viewModelScope.launch {
            if (repository.isRegistered.first()) loadVisibility()
        }
    }

    /** この端末をサーバーに登録し、続けて現在のスペック・状態を送る。 */
    fun register() = runExclusive {
        val imei = repository.imeiOnce() ?: return@runExclusive "IMEI が未設定です"
        enrollment.enroll(imei).getOrElse {
            return@runExclusive "登録に失敗しました: ${SmapassApi.describe(it)}"
        }
        loadVisibility()
        sendMeasurements()
        "サーバーに登録しました（初期設定は非公開です）"
    }

    /** 計測ログを今すぐ送る（スペックは変化があるときのみ）。 */
    fun sendNow() = runExclusive {
        when (val outcome = sendMeasurements()) {
            is MeasurementUploader.Outcome.Sent -> "送信しました"
            is MeasurementUploader.Outcome.Failed -> outcome.message
            is MeasurementUploader.Outcome.Skipped -> outcome.reason
        }
    }

    fun setPublic(isPublic: Boolean) = runExclusive {
        val target = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE
        enrollment.setVisibility(target).fold(
            onSuccess = { visibility ->
                local.update { it.copy(visibility = visibility) }
                if (visibility == Visibility.PUBLIC) "公開にしました" else "非公開にしました"
            },
            onFailure = { "公開設定を変更できませんでした: ${SmapassApi.describe(it)}" },
        )
    }

    /** 登録を終了して引き継ぎ可能にする（サーバーの登録終了 + 鍵破棄）。 */
    fun unregister() = runExclusive {
        enrollment.unregister().fold(
            onSuccess = {
                local.update { it.copy(visibility = null) }
                "登録を解除しました。新しい端末で登録し直せます"
            },
            onFailure = { "登録解除に失敗しました: ${SmapassApi.describe(it)}" },
        )
    }

    private suspend fun loadVisibility() {
        enrollment.lookup().onSuccess { device ->
            local.update { it.copy(visibility = device.visibility) }
        }
    }

    private suspend fun sendMeasurements(): MeasurementUploader.Outcome {
        // 送信用の状態は、前回の送信以降の異常終了をまとめて判定するため新しく取り直す。
        val since = repository.lastStatusSentAtOnce() ?: 0L
        val (spec, status) = withContext(Dispatchers.IO) {
            val imei = repository.imeiOnce()
            collector.collectSpec().copy(imei = imei) to collector.collectStatus(since)
        }
        return uploader.uploadNow(spec, status)
    }

    private fun runExclusive(block: suspend () -> String) {
        if (local.value.isBusy) return
        viewModelScope.launch {
            local.update { it.copy(isBusy = true, message = null) }
            val message = runCatching { block() }
                .getOrElse { "処理に失敗しました: ${SmapassApi.describe(it)}" }
            local.update { it.copy(isBusy = false, message = message) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                val repository = DeviceRepository(app)
                SettingsViewModel(
                    repository = repository,
                    enrollment = DeviceEnrollmentRepository(app, repository),
                    uploader = MeasurementUploader(app, repository),
                    collector = DeviceInfoCollector(app),
                )
            }
        }
    }
}
