package com.groupe.smapassmobile.ui.imei

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.groupe.smapassmobile.data.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImeiUiState(
    val imei: String = "",
    val isValid: Boolean = false,
)

class ImeiScreenViewModel(
    private val repository: DeviceRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImeiUiState())
    val uiState: StateFlow<ImeiUiState> = _uiState.asStateFlow()

    fun onImeiChange(input: String){
        if (input.length <= 15 && input.all { it.isDigit() }) {
            _uiState.update { it.copy(imei = input, isValid = isImei(input)) }
        }
    }

    fun save(onSaved: () -> Unit) {
        val current = _uiState.value
        if (!current.isValid) return
        viewModelScope.launch {
            repository.saveImei(current.imei)
            onSaved()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                ImeiScreenViewModel(DeviceRepository(app))
            }
        }
    }
}


/*LuhnアルゴリズムによるIMEIチェック*/
fun isImei(imei: String):Boolean {
    if (imei.length != 15 || !imei.all { it.isDigit()}){
        return false
    }

    var luhnSum = 0
    for(i in 0 until 15){
        var luhnSeg = imei[i].digitToInt()
        if(i % 2 == 1){
            luhnSeg *= 2
            if (luhnSeg >= 10) luhnSeg -= 9
        }
        luhnSum += luhnSeg
    }
    return luhnSum % 10 == 0
}

