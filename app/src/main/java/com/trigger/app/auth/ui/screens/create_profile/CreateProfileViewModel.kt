package com.trigger.app.auth.ui.screens.create_profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CreateProfileViewModel : ViewModel() {

    private val _profilePic = MutableStateFlow<Uri?>(null)
    val profilePic: StateFlow<Uri?> = _profilePic

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _bio = MutableStateFlow("")
    val bio: StateFlow<String> = _bio

    fun updateName(newName: String) { _name.value = newName }
    fun updateBio(newBio: String) { _bio.value = newBio }

    fun updateProfilePic(newPic: Uri) {
        _profilePic.value = newPic
    }

}