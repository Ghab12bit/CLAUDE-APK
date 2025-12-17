# FocusBlock - Digital Wellbeing App

A comprehensive Android app for managing screen time and blocking distracting apps. Similar to AppBlock but completely free and for personal use.

## Features

- **Quick Block**: Instantly block selected apps with one tap
- **Schedules**: Create time-based blocking schedules (Work, Sleep, Study, etc.)
- **Strict Mode**: Prevent yourself from disabling blocking
- **Hard Mode**: PIN + time lock for maximum commitment
- **Pomodoro Timer**: Built-in productivity timer with breaks
- **Statistics**: Track your blocking activity and saved time
- **Allowlist**: Keep essential apps always accessible
- **Beautiful Dark UI**: AppBlock-inspired modern dark theme

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34 (API Level 34)
- JDK 17 or newer
- Minimum Android Version: 8.0 (API 26)
- Target: Samsung Galaxy S23 Ultra and similar devices

## Building the APK

### Option 1: Using Android Studio (Recommended)

1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to this folder and open it
4. Wait for Gradle sync to complete
5. Go to Build > Build Bundle(s) / APK(s) > Build APK(s)
6. APK will be in `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Command Line

1. Copy `local.properties.template` to `local.properties`
2. Edit `local.properties` and set `sdk.dir` to your Android SDK path
3. Run:
   ```bash
   ./gradlew assembleDebug
   ```
4. APK will be in `app/build/outputs/apk/debug/app-debug.apk`

### Option 3: Release Build (Signed)

1. Generate a keystore:
   ```bash
   keytool -genkey -v -keystore focusblock.keystore -alias focusblock -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `keystore.properties`:
   ```
   storePassword=your_password
   keyPassword=your_key_password
   keyAlias=focusblock
   storeFile=../focusblock.keystore
   ```
3. Run:
   ```bash
   ./gradlew assembleRelease
   ```

## Installation

1. Enable "Install from unknown sources" in your S23 Ultra settings
2. Transfer the APK to your device
3. Tap the APK to install
4. Grant required permissions when prompted:
   - **Usage Access**: Required to detect foreground apps
   - **Display Over Apps**: Required to show blocking screen
   - **Accessibility Service** (Optional): Enhanced blocking
   - **Battery Optimization**: Disable for reliable blocking

## Permissions Explained

| Permission | Why It's Needed |
|------------|-----------------|
| PACKAGE_USAGE_STATS | Detect which app is currently open |
| SYSTEM_ALERT_WINDOW | Show the "app blocked" overlay |
| FOREGROUND_SERVICE | Keep blocking service running |
| RECEIVE_BOOT_COMPLETED | Restart blocking after device reboot |
| QUERY_ALL_PACKAGES | List installed apps to block |
| POST_NOTIFICATIONS | Show blocking status notification |
| VIBRATE | Haptic feedback when blocking |

## Architecture

- **MVVM Pattern**: Clean separation of concerns
- **Jetpack Compose**: Modern declarative UI
- **Room Database**: Local data persistence
- **Hilt**: Dependency injection
- **Kotlin Coroutines**: Async operations
- **Material 3**: Modern Android design

## Project Structure

```
app/src/main/java/com/focusblock/app/
├── database/           # Room entities, DAOs, repository
├── di/                 # Hilt dependency injection
├── model/              # Data models
├── receiver/           # Broadcast receivers
├── service/            # Background services
├── ui/                 # Compose UI screens
│   ├── components/     # Reusable components
│   ├── home/           # Home/Quick Block screen
│   ├── overlay/        # Blocking overlay
│   ├── schedules/      # Schedules screen
│   ├── settings/       # Settings screen
│   ├── statistics/     # Statistics screen
│   └── theme/          # Material theme
├── utils/              # Utility classes
└── viewmodel/          # ViewModels
```

## Troubleshooting

### App blocking not working?
1. Ensure Usage Access permission is granted
2. Disable battery optimization for FocusBlock
3. Enable Accessibility Service for better blocking
4. On Samsung: Disable "Put app to sleep" in battery settings

### Service stopping?
Samsung's aggressive battery management may stop the service:
1. Settings > Apps > FocusBlock > Battery > Unrestricted
2. Settings > Battery > Background usage limits > Never sleeping apps > Add FocusBlock

## License

Personal use only. Not for distribution on app stores.

## Version

1.0.0 - Initial release
