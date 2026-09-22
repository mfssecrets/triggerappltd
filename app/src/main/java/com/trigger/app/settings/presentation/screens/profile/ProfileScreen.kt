package com.trigger.app.settings.presentation.screens.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.canhub.cropper.CropImageContract
import com.trigger.app.R
import com.trigger.app.chats.presentation.view_profile_pic.ControlBlurOnScreen
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.components.AppBar
import com.trigger.app.core.presentation.ui.Settings
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.components.AppBottomBar
import com.trigger.app.core.presentation.ui.components.BottomBars
import com.trigger.app.core.presentation.ui.components.LoadingSpinner
import com.trigger.app.core.presentation.ui.components.UserIcon
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.DarkBlue
import com.trigger.app.core.utils.DefaultCropContract
import com.trigger.app.settings.presentation.screens.profile.components.EditProfilePopup
import com.trigger.app.settings.presentation.screens.profile.components.ProfileItem
import timber.log.Timber

@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val user by viewModel.user.collectAsState(null)
    val taskState by viewModel.taskState.collectAsState()

    var showNamePopup by remember { mutableStateOf(false) }
    var showBioPopup by remember { mutableStateOf(false) }

    var showProfilePicPopup by remember { mutableStateOf(false) }
    var previewProfilePicFullScreen by remember {
        mutableStateOf<String?>(null)
    }

    ControlBlurOnScreen(
        isPictureOnFullScreen = previewProfilePicFullScreen != null,
        profilePic = previewProfilePicFullScreen,
        dismissPicture = { previewProfilePicFullScreen = null }) {

        Box(modifier = Modifier.fillMaxSize()) {
            DefaultScreen(
                appBar = {
                    Box(Modifier.fillMaxWidth()) {
                        AppBar(
                            navController = navController,
                            appBarText = stringResource(R.string.profile),
                            onBackPressed = { navController.popBackStack() }
                        )
                        IconButton(
                            onClick = { navController.navigateSafely(Settings) },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                    }
                }
            ) {

                // Loading state — show a spinner while the user profile snapshot
                // listener hasn't emitted yet (initial `user == null`).
                if (user == null) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .padding(vertical = 30.dp)
                            .align(Alignment.CenterHorizontally)
                    ) {
                        UserIcon(
                            profilePic = user?.profilePic,
                            iconSize = 180.dp,
                            progressBarSize = 36.dp,
                            progressBarThickness = 3.dp,
                            borderIfUsingDefaultPic = 2.dp,
                            onClick = {
                                previewProfilePicFullScreen = user?.profilePic ?: ""
                            }
                        )

                        Image(
                            painter = painterResource(id = R.drawable.camera),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { showProfilePicPopup = true }
                                .background(DarkBlue)
                                .padding(9.dp)
                                .align(Alignment.BottomEnd),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                    }  // end of Box

                    Column(Modifier.padding(horizontal = 12.dp)) {
                    ProfileItem(
                        modifier = Modifier.padding(vertical = 12.dp),
                        startIcon = Icons.Outlined.Person,
                        title = stringResource(R.string.name),
                        data = user?.name ?: "",
                        textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                        onEditClicked = { showNamePopup = true }
                    )

                    ProfileItem(
                        modifier = Modifier.padding(vertical = 12.dp),
                        startIcon = Icons.Outlined.Person,
                        title = stringResource(R.string.username),
                        data = user?.username?.let { "@$it" } ?: "",
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        onEditClicked = null
                    )

                    ProfileItem(
                        modifier = Modifier.padding(vertical = 12.dp),
                        startIcon = Icons.Outlined.Person,
                        title = stringResource(R.string.about),
                        data = user?.bio ?: "",
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W400),
                        onEditClicked = { showBioPopup = true }
                    )

                    ProfileItem(
                        modifier = Modifier.padding(vertical = 12.dp),
                        startIcon = Icons.Outlined.Person,
                        title = stringResource(R.string.phone),
                        data = user?.number ?: "",
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        onEditClicked = null
                    )
                }  // end of Column
                }  // end of else (user != null)
            }


            if (showNamePopup) {
                EditProfilePopup(
                    popupTitle = stringResource(id = R.string.enter_your_name),
                    popupData = user?.name ?: "",
                    hidePopup = { showNamePopup = false },
                    saveNewChange = { newName -> viewModel.updateName(newName) }
                )
            }
            if (showBioPopup) {
                EditProfilePopup(
                    popupTitle = stringResource(id = R.string.enter_your_bio),
                    popupData = user?.bio ?: "",
                    hidePopup = { showBioPopup = false },
                    saveNewChange = { newBio -> viewModel.updateBio(newBio) }
                )
            }


            if (taskState is TaskState.LOADING) {
                (taskState as TaskState.LOADING).progress?.let { progress ->
                    LoadingSpinner(modifier = Modifier.align(Alignment.Center))
                }
            }

            val pickImageLauncher =
                rememberLauncherForActivityResult(contract = CropImageContract()) { imageURI ->
                    Timber.d("imageURI.uriContent is ${imageURI.uriContent}")

                    imageURI.uriContent?.let { uri ->
                        viewModel.updateProfilePic(uri)
                    }
                    showProfilePicPopup = false
                }

            if (showProfilePicPopup)
                pickImageLauncher.launch(DefaultCropContract)
        }

        // Profile is now a bottom-nav tab — render the bottom bar so users can
        // navigate away without going through Settings.
        AppBottomBar(
            currentBottomBar = BottomBars.Profile,
            navController = navController,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}


@Preview
@Composable
private fun PreviewProfileScreen() = AppTheme {
    ProfileScreen(navController = rememberNavController(), viewModel = ProfileViewModel())
}