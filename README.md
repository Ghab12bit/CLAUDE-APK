# FocusBlock - Digital Wellbeing App

A comprehensive Android app for managing screen time and blocking distracting apps. Similar to AppBlock but completely free and for personal use.

## Features

- **Quick Block**: Instantly block selected apps with one tap
- **Schedules**: Create time-based blocking schedules (Work, Sleep, Study, etc.)
- **Strict Mode**: Prevent yourself from disabling blocking
- **Hard Mode**: PIN + time lock for maximum commitment
- **Pomodoro Timer**: Built-in productivity timer with breaks
- **Focus Cycles**: Automatic blocking during scheduled focus periods
- **Mindful Reminders**: Gentle nudge at 30 min, firm reminder at 60 min of continuous usage
- **Insights Dashboard**: Track biggest distractions, peak hours, long sessions, and streaks
- **Context-Aware Suggestions**: Smart action suggestions based on your usage patterns
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

## Version History

### v1.5.0 - App Timer & Blocking Improvements (Latest)
**Commit:** `340adc9`

#### New Features
- **App Timer**: Set shared daily time limits across multiple apps
  - Custom time input (not just presets)
  - Accurate usage tracking using UsageEvents API
  - Enforced blocking when limit reached with override friction
  - 5-second countdown + 15-min override (once daily)
  - Integrates with Focus Cycle for combined enforcement

- **Unified Blocking System**:
  - All blocked apps now show their source (Quick Block, Schedule, App Timer, Focus Cycle)
  - Improved pickup detection with conservative counting
  - 30-second idle gap and 5-second debounce for accurate metrics

#### UI/UX Improvements
- Statistics/Insights screen now uses dark theme (consistent with app)
- Override countdown text now visible (was too dim)
- Pomodoro settings now functional with duration picker
- About dialog added with app info
- Chrome and other pre-installed apps now appear in app list
- Schedule save button no longer requires name (defaults to "Schedule")

#### Bug Fixes
- Fixed App Timer usage showing wrong time (was cumulative, now resets at midnight)
- Fixed Chrome not appearing in app list (system app filter whitelist)
- Fixed LinearProgressIndicator crashes in App Timer screens
- Fixed pickup metric inflation (was counting app switches as pickups)

### v1.4.0 - Insights & Mindful Reminders
**Commit:** `640e1da`

#### New Features
- **Mindful Session Reminders**:
  - Gentle nudge notification at 30 minutes of continuous app usage
  - Firm productivity reminder at 60 minutes with escalation
  - Works independently of app switching (periodic background check)
  - Gentle double-tap vibration for mindful reminders

- **Insights Dashboard**:
  - Biggest Distraction of the Day with peak hour detection
  - Most Productive Hour vs Weakest Hour tiles
  - Long Session Risk alerts (sessions > 40 min)
  - Focus Streak counter and Time Saved estimates
  - Context-aware action suggestions with CTAs

- **Smart Action Suggestions**:
  - "Peak distraction window detected" - suggests Focus Cycle
  - "Block [App] now?" - quick action for top distractions
  - Direct buttons to start existing blocking flows

#### Improvements
- Quick Focus timer now blocks immediately when timer ends (no app switch needed)
- Floating overlay auto-hides when tracked app is closed
- HomeScreen scrolling stability improvements with stable keys
- Better error handling for Insights data loading

#### Bug Fixes
- Fixed crash when scrolling Home page fast or repeatedly
- Fixed HourlyTile Row layout crash with inconsistent weighted children
- Fixed nullable type handling in insights calculations
- Added try-catch safety around all insights processing

### v1.3.0 - UI Polish
**Commit:** `16482f2`
- Modern, calm, premium UI styling
- Quick Block hero card with 24dp radius, 8dp elevation
- Enhanced stats cards with icon containers
- Schedule cards with accent bars and day chips
- Improved chart styling with value labels
- Better empty states with circular icons
- Refined color palette with depth system

### v1.2.0 - Gradle & Compatibility
**Commit:** `98c3f1f`
- Upgraded Gradle to 8.5 for Java 21 compatibility
- Fixed deprecated `rootProject.buildDir`
- Added gradle-wrapper.jar for building without local Gradle

### v1.1.0 - Stability & Features
**Commit:** `6da2a0b`
- Critical stability fixes (wake lock, ANR prevention)
- Fixed notification permission check for Android 13+
- UI improvements for Home, Schedule, and Statistics screens
- Added "Select Apps" button in Quick Block
- Made "Apps blocked" section clickable
- Added active block indicator with end time
- Added snackbar confirmation on block start
- Schedule time picker with 12-hour format
- Days of week selection in schedules

### v1.0.1 - Bug Fixes
**Commit:** `ce3b657`
- Fixed LinearProgressIndicator progress parameter
- Fixed Map.entries takeLast compatibility
- Fixed composable call in HardModeUnlockActivity
- Added missing Material 3 experimental APIs opt-in

### v1.0.0 - Initial Release
**Commit:** `29c4a9e`
- Complete FocusBlock digital wellbeing app
- Quick Block, Schedules, Strict Mode, Hard Mode
- Pomodoro Timer, Statistics, Allowlist
- Modern dark UI with Material 3

---

## Download Previous Builds

| Version | Commit | Download |
|---------|--------|----------|
| v1.5.0 | `340adc9` | [Latest Branch](../../tree/claude/add-hard-mode-unlock-bTrNn) |
| v1.4.0 | `640e1da` | [Previous](../../tree/640e1da) |
| v1.3.0 | `16482f2` | [UI Polish](../../tree/16482f2) |
| v1.0.1 | `ce3b657` | [V1.zip](https://github.com/user-attachments/files/24244521/V1.zip) |

> To build a specific version:
> ```bash
> git checkout <commit-hash>
> ./gradlew assembleDebug
> ```
