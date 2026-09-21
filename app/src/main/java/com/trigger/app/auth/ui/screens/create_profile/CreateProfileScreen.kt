package com.trigger.app.auth.ui.screens.create_profile

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.canhub.cropper.CropImageContract
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.CreateUsername
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.components.UserIcon
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.Montserrat
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientBottom
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientMid
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientTop
import com.trigger.app.core.utils.DefaultCropContract

@Composable
fun CreateProfileScreen(
    navController: NavController,
    snackbarHostState: SnackbarHostState,
    phoneNumber: String
) {
    val viewModel = viewModel<CreateProfileViewModel>()

    val name by viewModel.name.collectAsState()
    val bio by viewModel.bio.collectAsState()
    val profilePic by viewModel.profilePic.collectAsState()

    var galleryIsOpen by remember { mutableStateOf(false) }
    var shouldOpenGallery by remember { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(contract = CropImageContract()) { imageURI ->
            // Reset BOTH flags unconditionally (regardless of whether the user
            // picked an image, cancelled, or backed out) so a subsequent tap on
            // the profile picture icon re-opens the cropper. The previous code
            // only reset galleryIsOpen on the result callback, leaving
            // shouldOpenGallery = true after a cancel — which then re-launched
            // the cropper the moment the user touched the icon again.
            shouldOpenGallery = false
            galleryIsOpen = false

            imageURI.uriContent?.let { uri ->
                viewModel.updateProfilePic(uri)
            }
        }

    LaunchedEffect(key1 = shouldOpenGallery) {
        if (shouldOpenGallery && !galleryIsOpen) {
            galleryIsOpen = true
            launcher.launch(DefaultCropContract)
        }
    }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            WelcomeGradientTop,
            WelcomeGradientMid,
            WelcomeGradientMid,
            WelcomeGradientBottom,
        )
    )

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush)
            .imePadding()
    ) {
        DefaultScreen(
            modifier = Modifier
                .padding(top = 40.dp)
                .padding(horizontal = 16.dp),
            surfaceColor = Color.Transparent,
            backgroundColor = Color.Transparent,
            appBar = { Spacer(modifier = Modifier.height(0.dp)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.profile_info),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontFamily = QuickSand,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )

                Text(
                    text = stringResource(R.string.provide_name_and_profile_photo),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Box(Modifier.padding(top = 24.dp)) {
                    UserIcon(
                        profilePic = profilePic?.toString(),
                        iconSize = 120.dp,
                        progressBarSize = 32.dp,
                        progressBarThickness = 3.dp,
                        modifier = Modifier,
                        borderIfUsingDefaultPic = 2.dp,
                        onClick = { shouldOpenGallery = true }
                    )

                    IconButton(
                        onClick = { shouldOpenGallery = true },
                        modifier = Modifier.align(Alignment.BottomEnd),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.White,
                            contentColor = WelcomeGradientTop
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.select_profile_picture_from_galley),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                CreateProfileTextField(
                    modifier = Modifier.padding(top = 28.dp),
                    value = name,
                    placeHolderText = stringResource(R.string.enter_your_name),
                    onValueChange = { viewModel.updateName(it) }
                )

                CreateProfileTextField(
                    modifier = Modifier.padding(top = 14.dp),
                    value = bio,
                    placeHolderText = stringResource(id = R.string.enter_your_bio),
                    onValueChange = { viewModel.updateBio(it) }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        navController.navigateSafely(
                            CreateUsername(
                                phoneNumber = phoneNumber,
                                name = name.trim(),
                                bio = bio.trim(),
                                profilePic = profilePic?.toString()
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = WelcomeGradientTop
                    ),
                    enabled = name.isNotBlank()
                ) {
                    Text(
                        text = stringResource(R.string.next),
                        fontFamily = QuickSand,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun CreateProfileTextField(
    value: String,
    placeHolderText: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
            focusedContainerColor = Color.White.copy(alpha = 0.14f),

            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,

            cursorColor = Color.White,
            selectionColors = TextSelectionColors(
                handleColor = Color.White,
                backgroundColor = WelcomeGradientTop.copy(alpha = 0.35f)
            ),

            unfocusedSupportingTextColor = Color.Transparent,
            focusedSupportingTextColor = Color.Transparent,

            unfocusedIndicatorColor = Color.White.copy(alpha = 0.6f),
            focusedIndicatorColor = Color.White
        ),
        keyboardOptions = KeyboardOptions.Default.copy(
            keyboardType = KeyboardType.Text,
            showKeyboardOnFocus = false
        ),
        textStyle = TextStyle(
            fontFamily = Montserrat,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        ),
        placeholder = {
            Text(
                placeHolderText,
                color = Color.White.copy(alpha = 0.5f),
                fontFamily = Montserrat,
                fontSize = 15.sp,
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview
@Composable
private fun PreviewCreateProfileScreen() = AppTheme {
    val snackbarHostState = SnackbarHostState()

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) {
        CreateProfileScreen(
            navController = rememberNavController(),
            snackbarHostState,
            phoneNumber = "+254794940110"
        )
    }
}
