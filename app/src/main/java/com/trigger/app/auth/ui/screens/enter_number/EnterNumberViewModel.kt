package com.trigger.app.auth.ui.screens.enter_number

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.da_chelimo.compose_ccp.model.PickerUtils
import com.da_chelimo.compose_countrycodepicker.libs.Country
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

class EnterNumberViewModel : ViewModel() {

    private val _number = MutableStateFlow("")
    val number: StateFlow<String> = _number

    private val _country = MutableStateFlow(PickerUtils.defaultCountry)
    val country: StateFlow<Country> = _country

    val numberWithCountryCode =
        number.map { "${country.value.phoneNoCode}$it".trim() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _taskState = MutableStateFlow<TaskState?>(null)
    val taskState: StateFlow<TaskState?> = _taskState

    private val _shouldNavigateToEnterCode = MutableStateFlow(false)
    val shouldNavigateToEnterCode: StateFlow<Boolean> = _shouldNavigateToEnterCode

    fun updateNumber(newNumber: String) {
        // Accept only digits, strip anything else (spaces, dashes, parens, etc.)
        val digits = newNumber.filter { it.isDigit() }
        _number.value = digits
        verifyNumber()
    }

    fun updateCountry(newCountry: Country) {
        _country.value = newCountry
        verifyNumber()
    }

    /**
     * Validate the typed phone number against the selected country's expected
     * length. Falls back to the ITU-T E.164 range (7-15 digits) for countries
     * we don't have an exact length for.
     */
    fun verifyNumber() {
        val digits = number.value

        // Empty input is not yet an error — just no verdict.
        if (digits.isEmpty()) {
            _taskState.value = null
            return
        }

        val expectedLength = expectedPhoneLength(country.value)

        _taskState.value = when {
            expectedLength != null && digits.length < expectedLength ->
                TaskState.DONE.ERROR(R.string.number_too_short)
            expectedLength != null && digits.length > expectedLength ->
                TaskState.DONE.ERROR(R.string.number_too_long)
            // E.164 fallback range (without country code)
            expectedLength == null && digits.length < MIN_LOCAL_LENGTH ->
                TaskState.DONE.ERROR(R.string.number_too_short)
            expectedLength == null && digits.length > MAX_LOCAL_LENGTH ->
                TaskState.DONE.ERROR(R.string.number_too_long)
            else -> TaskState.DONE.SUCCESS
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

    companion object {
        // E.164 says the full international number (incl. country code) is max 15 digits.
        // So without the country code the local part is at most ~13. We allow 7-13 as a
        // sensible fallback for any country not in the explicit table below.
        private const val MIN_LOCAL_LENGTH = 7
        private const val MAX_LOCAL_LENGTH = 13

        /**
         * Mobile/local number length (excluding country code) for countries the
         * app commonly serves. Returns null if unknown — caller should fall back
         * to the E.164 range.
         *
         * Sources: ITU-T E.164 national numbering plans.
         */
        private val COUNTRY_LENGTH: Map<String, Int> = mapOf(
            // South Asia
            "IN" to 10,  "PK" to 10,  "BD" to 10,  "LK" to 10,  "NP" to 10,
            // East / Southeast Asia
            "CN" to 11,  "JP" to 10,  "KR" to 9,   "TW" to 9,   "TH" to 9,
            "VN" to 9,   "ID" to 9,   "PH" to 10,  "MY" to 9,   "SG" to 8,
            "HK" to 8,
            // Middle East
            "AE" to 9,   "SA" to 9,   "QA" to 8,   "KW" to 8,   "BH" to 8,
            "OM" to 8,   "JO" to 9,   "LB" to 7,   "IL" to 9,   "TR" to 10,
            "IR" to 10,  "IQ" to 10,
            // Europe
            "GB" to 10,  "IE" to 9,   "FR" to 9,   "DE" to 10,  "IT" to 10,
            "ES" to 9,   "PT" to 9,   "NL" to 9,   "BE" to 9,   "CH" to 9,
            "AT" to 10,  "SE" to 9,   "NO" to 8,   "DK" to 8,   "FI" to 9,
            "PL" to 9,   "RU" to 10,  "UA" to 9,   "GR" to 10,  "CZ" to 9,
            "RO" to 9,
            // Americas
            "US" to 10,  "CA" to 10,  "MX" to 10,  "BR" to 11,  "AR" to 10,
            "CO" to 10,  "CL" to 9,   "PE" to 9,   "VE" to 10,
            // Africa
            "KE" to 9,   "NG" to 10,  "ZA" to 9,   "EG" to 10,  "GH" to 9,
            "UG" to 9,   "TZ" to 9,   "MA" to 9,   "DZ" to 9,   "TN" to 8,
            "ET" to 9,   "CM" to 9,   "SN" to 9,   "CI" to 10,
            // Oceania
            "AU" to 9,   "NZ" to 8,   "FJ" to 7,
        )

        fun expectedPhoneLength(country: Country): Int? =
            COUNTRY_LENGTH[country.code.uppercase()]
    }
}
