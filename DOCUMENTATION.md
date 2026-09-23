# Trigger App Documentation

## Project Identity

- Application ID: `com.trigger.app`
- Android namespace: `com.trigger.app`
- Firebase project: `trigger-app-cd138`
- Module: `app`

## Requirements

- Android Studio with Android SDK 34
- JDK 21 or a JDK supported by the Android Gradle Plugin
- Firebase project access

## Firebase Setup

Place the Firebase configuration file at:

```text
app/google-services.json
```

The file must contain the Android Firebase client whose package name is `com.trigger.app`. Do not include configuration for other applications. The file is excluded by `.gitignore` because it contains project-specific service configuration.

Before running the app, add the debug SHA-1 fingerprint to the Firebase project and enable the Firebase services used by the app, including Authentication, Cloud Firestore, Realtime Database, and Cloud Storage as applicable.

## Build

From the repository root, configure `local.properties` with the local Android SDK path if Android Studio has not created it, then run:

```bash
./gradlew :app:assembleDebug
```

If the wrapper is not executable after extracting the repository, use:

```bash
bash ./gradlew :app:assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

## User Onboarding

New users complete the following flow:

1. Enter and verify a phone number with Firebase Phone Authentication.
2. Create a profile with a name, bio, and optional profile picture.
3. Choose a unique username on the username onboarding page.

Usernames are normalized to lowercase, must be 5-24 characters, and may contain letters, numbers, and underscores. Availability is checked live, and the final profile save atomically reserves the username in Firestore.

## Dashboard

The primary dashboard tabs are:

- Chats
- Groups
- Stories
- Calls

Groups and Calls currently show their planned-feature state. The Chats header includes Message Requests, and the central action starts a conversation through phone contacts or username search.

## User Connections

Users can find other registered users in two ways:

- Phone contact matching, using the device contacts permission
- Username search, using the Firestore username index

Direct conversations are stored in Firestore under `chat_details` and each participant receives a personalized chat reference.

## Chat Moderation

Chat details provide:

- Block User confirmation, loading, and success states
- Report User page with reason validation, loading, cancellation, and success states

Moderation data is stored in:

```text
users/{uid}/blockedUsers/{blockedUserId}
reports/{reportId}
```

## Settings And Account Pages

The profile header opens Settings, which includes:

- Privacy options and Blocked Users management
- Verified Badge page
- Create my Page entry point
- Help page and support contact action
- Terms of Service and Privacy Policy links
- Sign out and Delete Account

The Verified Badge and Create my Page screens currently provide their UI entry points but do not submit backend applications yet.

## Message Requests

The mail icon in the Chats header opens the Message Requests page. The page currently provides its empty state and is ready for request storage plus accept/decline actions.

## Project Structure

- `app/src/main/java/com/trigger/app/`: Android application source
- `app/src/main/res/`: Compose and Android resources
- `compose_ccp/`: Local country-code picker library module
- `app/google-services.json`: Local Firebase configuration
- `app/src/main/java/com/trigger/app/auth/`: Phone authentication and onboarding
- `app/src/main/java/com/trigger/app/chats/`: Chats, connections, requests, and messaging
- `app/src/main/java/com/trigger/app/core/repo/moderation/`: Block and report persistence
- `app/src/main/java/com/trigger/app/settings/`: Profile, settings, privacy, Help, and account pages

## Tests

Run the unit tests with:

```bash
bash ./gradlew :app:testDebugUnitTest
```

Instrumentation tests require a connected Android device or emulator:

```bash
bash ./gradlew :app:connectedDebugAndroidTest
```
