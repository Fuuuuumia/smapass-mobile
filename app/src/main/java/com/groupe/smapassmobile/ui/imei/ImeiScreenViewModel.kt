package com.groupe.smapassmobile.ui.imei

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ImeiUiState(
    val imei: String = "",
    val isValid: Boolean = false,
)

class ImeiScreenViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ImeiUiState())
    val uiState: StateFlow<ImeiUiState> = _uiState.asStateFlow()

    fun onImeiChange(input: String){
        if (input.length <= 15 && input.all { it.isDigit() }) {
            _uiState.update { it.copy(imei = input, isValid = isImei(input)) }
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

