package com.trigger.app.main

import androidx.lifecycle.ViewModel
import com.trigger.app.core.domain.User
import com.trigger.app.core.presentation.ui.theme.StatusBars
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow

class MainViewModel(
    private val userRepo: UserRepo = UserRepoImpl()
) : ViewModel() {

    val statusBars = MutableStateFlow<StatusBars?>(null)

    suspend fun fetchCurrentUser(): User? =
        Firebase.auth.uid?.let { uid ->
            userRepo.getUserFromUID(uid)
        }


    fun updateStatusBar(newStatusBars: StatusBars) { statusBars.value = newStatusBars }
}