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

## Project Structure

- `app/src/main/java/com/trigger/app/`: Android application source
- `app/src/main/res/`: Compose and Android resources
- `compose_ccp/`: Local country-code picker library module
- `app/google-services.json`: Local Firebase configuration

## Tests

Run the unit tests with:

```bash
bash ./gradlew :app:testDebugUnitTest
```

Instrumentation tests require a connected Android device or emulator:

```bash
bash ./gradlew :app:connectedDebugAndroidTest
```
