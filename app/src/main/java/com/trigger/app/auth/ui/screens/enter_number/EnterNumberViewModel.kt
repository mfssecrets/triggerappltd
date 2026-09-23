package com.trigger.app.auth.ui.screens.enter_number

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.da_chelimo.compose_ccp.model.PickerUtils
import com.da_chelimo.compose_countrycodepicker.libs.Country
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

class EnterNumberViewModel : ViewModel() {

    private val _number = MutableStateFlow("")
    val number: StateFlow<String> = _number

    private val _country = MutableStateFlow(PickerUtils.defaultCountry)
    val country: StateFlow<Country> = _country

    // FIX #3: Uses Google libphonenumber for proper E.164 validation.
    // No more hardcoded COUNTRY_LENGTH map with IN=10, US=10, etc.
    val numberWithCountryCode =
        combine(number, country) { n, c -> "${c.phoneNoCode}$n".trim() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _taskState = MutableStateFlow<TaskState?>(null)
    val taskState: StateFlow<TaskState?> = _taskState

    private val _shouldNavigateToEnterCode = MutableStateFlow(false)
    val shouldNavigateToEnterCode: StateFlow<Boolean> = _shouldNavigateToEnterCode

    fun updateNumber(newNumber: String) {
        _number.value = newNumber.filter { it.isDigit() }
        verifyNumber()
    }

    fun updateCountry(newCountry: Country) {
        _country.value = newCountry
        verifyNumber()
    }

    // FIX #3: libphonenumber validation — parse + isValidNumber + E.164 format.
    fun verifyNumber() {
        val digits = number.value
        if (digits.isEmpty()) {
            _taskState.value = null
            return
        }

        val phoneUtil = PhoneNumberUtil.getInstance()
        val fullNumber = "${country.value.phoneNoCode}$digits"

        try {
            // Parse with the country's ISO code (e.g., "IN", "US", "GB").
            val parsed = phoneUtil.parse(fullNumber, country.value.code.uppercase())

            if (phoneUtil.isValidNumber(parsed)) {
                _taskState.value = TaskState.DONE.SUCCESS
            } else {
                _taskState.value = TaskState.DONE.ERROR(R.string.number_too_short)
            }
        } catch (e: Exception) {
            // libphonenumber throws NumberParseException for invalid input.
            Timber.d("verifyNumber: parse failed for '$fullNumber' — ${e.message}")
            _taskState.value = TaskState.DONE.ERROR(R.string.number_too_short)
        }
    }

    fun navigateToEnterCode() {
        if (taskState.value is TaskState.DONE.SUCCESS)
            _shouldNavigateToEnterCode.value = true
        Timber.d("shouldNavigateToEnterCode.value is ${shouldNavigateToEnterCode.value}")
    }

    fun resetShouldNavigate() {
        _shouldNavigateToEnterCode.value = false
    }

    // FIX #3: Returns the E.164-formatted number for Firebase Phone Auth.
    fun getE164Number(): String? {
        val phoneUtil = PhoneNumberUtil.getInstance()
        val fullNumber = "${country.value.phoneNoCode}${number.value}"
        return try {
            val parsed = phoneUtil.parse(fullNumber, country.value.code.uppercase())
            phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: Exception) {
            Timber.e(e, "getE164Number: failed to format '$fullNumber'")
            fullNumber  // fallback to raw concatenation
        }
    }
}
