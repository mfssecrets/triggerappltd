package com.trigger.app.chats.presentation.select_contact.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.trigger.app.R
import com.trigger.app.chats.presentation.select_contact.components.ContactPreview
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.components.LoadingSpinner
import com.trigger.app.core.presentation.ui.navigateSafelyAndPopTo
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.theme.Poppins
import com.trigger.app.core.presentation.ui.theme.QuickSand
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber

@Composable
fun SelectContactScreen(
    context: Context,
    navController: NavController,
    viewModel: SelectContactsViewModel = koinViewModel()
) {
    val contactsOnTriggerApp by viewModel.contactsOnTriggerApp.collectAsState(initial = null)
    val shouldNavigateToActualChat by viewModel.shouldNavigateToActualChat.collectAsState()
    val usernameSearchResult by viewModel.usernameSearchResult.collectAsState()
    val searchStatus by viewModel.searchStatus.collectAsState()
    var usernameQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.fetchContactsOnTriggerApp(context)
    }

    LaunchedEffect(key1 = shouldNavigateToActualChat) {
        if (shouldNavigateToActualChat != null) {
            navController.navigateSafelyAndPopTo(
                route = shouldNavigateToActualChat!!,
                popTo = AllChats,
                isInclusive = false
            )
            viewModel.resetShouldNavigateToActualChat()
        }
    }


    DefaultScreen(
        navController = navController,
        appBarText = stringResource(R.string.select_contact),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 4.dp)
    ) {

        Text(
            text = stringResource(R.string.contacts_on_trigger_app),
            fontFamily = QuickSand,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(2.dp))

        OutlinedTextField(
            value = usernameQuery,
            onValueChange = {
                usernameQuery = it
                viewModel.searchByUsername(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            singleLine = true,
            prefix = { Text("@") },
            label = { Text(stringResource(R.string.search_by_username)) }
        )

        usernameSearchResult?.let { contact ->
            ContactPreview(
                contact = contact,
                openProfilePic = {},
                startConversation = { viewModel.startOrResumeConversation(contact) },
                viewProfile = {
                    navController.navigateSafely(
                        com.trigger.app.core.presentation.ui.ChatDetails(
                            chatId = "",
                            otherUserId = contact.uid
                        )
                    )
                }
            )
        }

        // FIX: show clear feedback below the search field so the user knows
        // what's happening — was previously showing nothing during the 350ms
        // debounce OR when the search returned null.
        when (searchStatus) {
            SelectContactsViewModel.SearchStatus.TOO_SHORT -> {
                Text(
                    text = "Username must be at least 5 characters",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp)
                )
            }
            SelectContactsViewModel.SearchStatus.SEARCHING -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp).padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Searching for @$usernameQuery...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            SelectContactsViewModel.SearchStatus.NOT_FOUND -> {
                Text(
                    text = "No user found for \"@$usernameQuery\". Either the username doesn't exist or it's your own username (you can't chat with yourself).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)
                )
            }
            SelectContactsViewModel.SearchStatus.ERROR -> {
                Text(
                    text = "Search failed. Check your connection and try again.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)
                )
            }
            // IDLE + FOUND don't show extra text (FOUND shows the ContactPreview above)
            SelectContactsViewModel.SearchStatus.IDLE,
            SelectContactsViewModel.SearchStatus.FOUND -> Unit
        }

        if (contactsOnTriggerApp == null) { // Network call hasn't returned yet
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingSpinner()
            }
        } else if (contactsOnTriggerApp!!.isEmpty()) { // No contacts on Trigger App
            Box(Modifier.fillMaxSize(0.85f).align(Alignment.CenterHorizontally)) {
                Column(
                    Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.no_users_on_trigger_app),
                        contentDescription = null,
                        modifier = Modifier.size(150.dp)
                    )

                    Text(
                        text = stringResource(R.string.none_of_your_contacts_is_on_trigger_app),
                        fontFamily = Poppins,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
//                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth(0.75f)
                    )
                }
            }
        } else { // There are contacts on Trigger App
            LazyColumn {
                items(contactsOnTriggerApp!!) { contact ->
                    ContactPreview(
                        contact = contact,
                        openProfilePic = {
                            // TODO: Open DP preview
                        },
                        startConversation = {
                            Timber.d("Navigating with contact as $contact")
                            viewModel.startOrResumeConversation(contact)
                        },
                        viewProfile = {
                            // FIX: tap → view the other user's profile via
                            // ChatDetails route (chatId empty — screen shows
                            // user info + a "Start chat" affordance).
                            navController.navigateSafely(
                                com.trigger.app.core.presentation.ui.ChatDetails(
                                    chatId = "",
                                    otherUserId = contact.uid
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewSelectContactScreen() {
    SelectContactScreen(
        context = LocalContext.current,
        navController = rememberNavController()
    )
}