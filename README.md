# Home

A minimalist Android home screen written in Kotlin for Android 11+ (API 30).

- An empty query displays only pinned applications.
- Fuzzy app name search: supports substrings, missing characters, and up to two typos for longer queries.
- Most relevant match is positioned at the bottom, directly above the search bar. Pressing Enter launches it.
- Search bar and app list are anchored to the bottom of the visible area above the keyboard.
- Long-press in search to pin/unpin an app. Favorite apps on the home screen can be reordered via drag-and-drop on long press. Pinned state and custom order persist across launches.
- Keyboard is requested upon opening or returning to Home. Users can dismiss it using the system back button; Android manages the final IME state.
- Material You dynamic system colors on Android 12+; light and dark theme tracks the system setting. Android 11 uses fallback colors. Keyboard styling is handled by the keyboard itself.
- Full work profile and multi-user support: discovers, badges, and launches apps across all profiles (personal, work, cloned) using `LauncherApps` and `UserManager`. Pinned apps track profile IDs independently.
- No network access, no ads, and no `QUERY_ALL_PACKAGES` permission.

## Building

Open the project in Android Studio with JDK 17 and Android SDK 35, and let Gradle sync.
Alternatively, specify the SDK location in `local.properties` (`sdk.dir=/path/to/sdk`) and run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Installation

Install the APK on your device. Set Home as your default launcher in Android Settings → Apps → Default apps → Home app.
Type an app name and long-press the search result to pin it to your home screen. On the home screen, long-press and drag apps to reorder them.

## Testing on Device

1. Pin several applications, clear the search query, and restart the launcher: pinned apps remain.
2. Type `tlgrm` or `telegarm` with Telegram installed: it should match and appear.
3. Verify that the most relevant result is at the bottom and pressing Enter launches it.
4. Return home using the Home gesture/button: search query clears and the keyboard opens.
5. Test gesture and 3-button navigation, screen orientation changes, and long lists: the input field stays above the keyboard and the list scrolls properly.

Built with standard Android Views and WindowInsets for system bars and IME handling.
Keyboard behavior follows the [official Android documentation](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility).

## Local Development Tools (macOS)

CLI tools installed via Homebrew following the ["Emulator without Studio" guide](https://telegra.ph/EHmulyator-bez-studii-05-01):
`openjdk`, `android-commandlinetools`, `platform-tools`, `emulator`, Android 30, and system image `google_apis_playstore;arm64-v8a`.

- SDK: `/opt/homebrew/share/android-commandlinetools` (also symlinked in `~/Library/Android/sdk`).
- AVD: `android30`, Pixel profile.
- Open a new terminal for updated PATH, then run `emulator -avd android30` or `./scripts/emulator.sh`.
- For builds, run `./scripts/build.sh`: selects JDK 17, as the latest Homebrew OpenJDK is too new for this Gradle version.
- Install to running emulator: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions

- **Checks**: Runs on every push and pull request. Executes unit tests, Android Lint, and builds the debug APK. APK and build reports are saved as artifacts.
- **Release**: Actions → Release → Run workflow. Provide the version name (`1.0.0`) and an integer `version_code` greater than all previous releases. Toggle prerelease if needed.
- Release workflow runs unit tests and Lint, builds a signed release APK, and publishes a GitHub Release tagged `v<version>` with the APK and `SHA256SUMS.txt`. Existing tags are not overwritten.
- Signing uses GitHub Secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`, alias `home`. A single key is reused across all releases to allow seamless in-place updates.
- Release APKs are signed with a production key: any previously installed debug version must be uninstalled prior to installing the release APK (local pinned apps will be reset upon uninstall).
- Releases are triggered manually; only Checks run automatically on push.
