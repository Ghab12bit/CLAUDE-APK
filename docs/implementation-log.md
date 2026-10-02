# FocusBlock — Implementation log

> **Re-baseline (2026-10-02):** the Phase 0 audit below was run against `92fd585` (the March code).
> The owner then confirmed that work continues on the recovery line
> `codex/focusblock-recovery` → `codex/blocking-first-20261001` (`94a12aa`), which already fixes R1 (timed blocks),
> R9 (destructive migration → v16 preserving migrations), parts of R4 (dialler/telecom exemptions), R7 (widget lock),
> and adds Block/Rules/Activity screens, a fixed signing key and emulator CI. Findings below that the recovery
> branch fixed are marked as such in the Phase 1+ entries. The owner authorised all phases, commits and pushes
> ("Do everything and give me the final app").


Governing spec: *FocusBlock — Claude Code Build Prompt* (Oct 2, 2026). Section numbers below (§) refer to that document.

---

## Phase 0 — Baseline (2026-10-02)

**Status: inspection complete. No source files changed.** The only file added is this log.

### 0.1 Branch and working tree

| Item | Result |
|---|---|
| Branch | `claude/new-session-hf7zu9` @ `92fd585` ("Fix ANR risk in verifyPinAndStop by caching PIN") |
| Working tree | Clean before this log was added |
| Other branches | `claude/add-hard-mode-unlock-bTrNn` points to the same history as HEAD (ancestor, 0 commits ahead), so there is no divergent recovery work |
| History | 50 commits; the last 6 are "fix compilation / critical bug" commits from 2026-03-24 to 2026-03-30 |
| Size | ~31,000 lines across 47 Kotlin files, in a single `:app` module |

### 0.2 Build, test, lint, device: **none could run**

| Step | Result | Evidence |
|---|---|---|
| `./gradlew assembleDebug` | **NOT RUN — blocked by environment** | No Android SDK is installed (`ANDROID_HOME` is unset). The SDK, AGP 8.2.2 and AndroidX artifacts are all served from `dl.google.com`. `maven.google.com` 301-redirects to it, and the egress proxy denies it: `CONNECT tunnel failed, response 403` (proxy status: `connect_rejected … dl.google.com:443`). |
| Unit tests | **NONE EXIST** | There is no `app/src/test` or `app/src/androidTest`. `build.gradle` declares JUnit, Espresso and compose-test dependencies, but the source sets have no files. |
| Lint | NOT RUN | Same reason as the build |
| Device or emulator capture of current blocking behaviour | **NOT DONE — no device or emulator available** | Recorded as a gap. It is not marked as passed (§11.3). |

**Pre-existing failures:** unknown. The last commits claim the build compiles ("Fix all compilation errors across codebase", `52a0de4`), but I could not verify that here. The **first action of Phase 1** must be a real build on a machine with the SDK, to confirm the baseline compiles.

Toolchain facts:
- Gradle wrapper 8.5, AGP 8.2.2, Kotlin 1.9.20, Compose compiler 1.5.4, BOM 2023.10.01
- compileSdk/targetSdk 34, minSdk 26, `buildToolsVersion "33.0.1"`
- JDK 17 target; the container has JDK 21
- Room 2.6.1 (KSP), Hilt 2.48.1, WorkManager 2.9.0, Coil 2.5.0

### 0.3 Architecture map (as it actually is)

**Navigation.** `ui/MainActivity.kt:135-237` defines a NavHost with 4 bottom tabs:

| Route | Label shown | Screen | ViewModel |
|---|---|---|---|
| `home` | **Blocking** | `HomeScreen` (6,204 lines, one LazyColumn) | `HomeViewModel` (2,469 lines) |
| `schedules` | **Schedules** | `SchedulesScreen` | `SchedulesViewModel` |
| `statistics` | **Insights** | `StatisticsScreen` (+ `PeakTimeDetailScreen`) | `InsightsViewModel` |
| `settings` | **Profile** | `SettingsScreen` | `SettingsViewModel` |

- Onboarding is a 4-page promo pager with no permission step (`ui/onboarding/OnboardingScreen.kt`). Page 4 advertises App Groups, which cannot be reached.
- `MainActivity` reads the onboarding flag straight from the DAO.

**Enforcement.**
- `service/FocusBlockAccessibilityService.kt` (3,829 lines) is the primary enforcer:
  - It handles `TYPE_WINDOW_STATE_CHANGED`, then runs `shouldBlockApp` (`:1796-1996`). The order is: allowlist → global limit → App Timer → Strict → Quick Block/Pomodoro → Focus Cycle → Schedules.
  - It runs ~7 handler loops: 1 s Quick Block expiry, 10 s App Timer, 30 s Global, 30 s Strict, 60 s Schedules, 60 s reminders, 5 min usage/auto-block.
- `service/AppBlockingService.kt` is the foreground fallback:
  - It polls `UsageStatsManager.queryEvents` every 500 ms and enforces allowlist → Quick Block (ignores endTime) → Schedules → `BlockedApp.isBlocked`.
  - It stands down completely while the accessibility service is running.
- The intervention UI is three different screens:
  - `BlockedAppActivity` (the main one)
  - `AppTimerReflectionActivity` (App Timer)
  - `HardModeUnlockActivity` (**never launched**)
- `blocking/UnifiedBlockingManager.kt` has **zero references**. It has its own third rule set, which includes App Groups and Bedtime.

**Receivers.**

| Receiver | Registered for | Does | Targeted by |
|---|---|---|---|
| BootReceiver | BOOT_COMPLETED, QUICKBOOT_POWERON | Starts AppBlockingService only | system |
| ScheduleAlarmReceiver | (no filter) | Refreshes the FGS notification | **nobody** |
| PomodoroReceiver | (no filter) | Notification and sound | **nobody** |
| AppTimerExtendReceiver | ACTION_EXTEND_APP_TIMER | **Permanently** sets `dailyLimitMinutes += 15` | A11y notification action |
| ChargingStateReceiver | POWER_(DIS)CONNECTED | Insight counters in SharedPreferences | system |

- There is no MY_PACKAGE_REPLACED, TIMEZONE_CHANGED or TIME_SET handling.
- **There is no AlarmManager usage anywhere** (`grep AlarmManager|setExact` returns 0 hits).
- The manifest has no exact-alarm permission.

**Data.**
- Room `FocusBlockDatabase` is at **version 12** with `exportSchema=false`, **no migrations, and `.fallbackToDestructiveMigration()`** (`database/FocusBlockDatabase.kt:42-43,91`). It has 24 entities, all in `database/entity/BlockedApp.kt`.
- There are 23 DAOs plus a combined `FocusBlockDao` used by the widget and MainActivity, with `FocusBlockRepository` on top.
- Settings, including Strict, legacy Hard Mode, the **plaintext PIN** and the saved Quick Block apps, live in a key/value `settings` table.
- The DataStore dependency is declared but unused.
- SharedPreferences is used only by workers, the charging receiver and the usage backfill.

**Workers.**
- `DailyInsightsWorker` runs daily at about 21:00.
- `PeakTimeReminderWorker` runs every 15 min. It is enqueued on every Quick Block or Focus Cycle start and **never cancelled**.

**Widget.** `widget/FocusBlockWidget.kt` writes sessions directly through the DAO (see R7).

### 0.4 §8.3 known risks: verification

| # | Risk | Result | Evidence |
|---|---|---|---|
| 1 | HomeScreen.kt ~6,000 lines | **Confirmed** (6,204) | It mixes Quick Block, App Timer, daily limit, Hard Mode B, bedtime, suggestions, Focus Cycle, Strict, schedules preview, insights preview and 12 dialogs. About 15 composables in it are unused (`StrictModeCard`, `FocusCycleCard`, `AppTimerCard`, `InsightsSection` and its subtree, `WeeklySummaryCard`, …). |
| 2 | Statistics uses InsightsViewModel; StatisticsViewModel unused | **Confirmed** | `ui/statistics/StatisticsScreen.kt:32,46`. `StatisticsViewModel` has no references. A second insights pipeline also exists: `HomeViewModel.loadInsights` feeds the Home preview card. |
| 3 | Pomodoro UI uses QuickBlockSession; Pomodoro entities and receivers disconnected | **Confirmed, and worse** | The UI **discards** work and break minutes (`HomeScreen.kt:536-543`) and starts a plain open-ended Quick Block. `HomeViewModel.startPomodoroSession` (`:1006`) has no callers. The `PomodoroSession` entity and DAO are unused, and `PomodoroReceiver` is never targeted. |
| 4 | ScheduleAlarmReceiver without AlarmManager path | **Confirmed** | There is no AlarmManager in the codebase. Schedules are enforced only by A11y polling and events, plus the FGS poll. |
| 5 | App Groups unreachable | **Confirmed** | There is no UI call site (`AppGroupCard.kt` is unused) and no insert path. The only consumer is the dead `UnifiedBlockingManager`. |
| 6 | Work Mode / Digital Detox dialogs orphaned | **Confirmed** | `Dialogs.kt:1935` and `:2101` have 0 call sites. `TimeLimitSetupDialog` and `FocusCycleOverrideDialog` are also unused. |
| 7 | Widget accesses DAO directly | **Confirmed, and it is a bypass** | `FocusBlockWidget.kt:47-65`: it stops the active session with **no Strict, Hard or PIN check**. It never notifies either service. On start, it blocks every `isBlocked=1` app indefinitely. |
| 8 | Two Quick Block implementations | **Confirmed** | `ui/home/HomeScreen.kt:1022` is used. `ui/components/QuickBlockCard.kt:28` is unused. |
| 9 | Temporary selections mixed with permanent settings | **Confirmed** | `startQuickBlock` sets `blocked_apps.isBlocked=true` (`HomeViewModel.kt:874-884`). It is cleared only on a UI stop; A11y expiry (`:474`), widget stop and Pomodoro never clear it. `HardModeUnlockActivity:235` clears **all** `isBlocked` rows. A11y stopped reading `isBlocked` for this reason (`:1984-1992`), but the fallback still enforces it, so the two services disagree. |
| 10 | UI state used as truth | **Confirmed** | `HomeViewModel.startTimerUpdates` (`:776-786`) runs a `while(true){delay(1000)}` loop. That loop is what ends Quick Block (`:805-817`) and disables Strict in the DB on expiry (`:819-838`). It also polls permissions every second. |
| 11 | Accessibility and fallback enforce different policy sets | **Confirmed** | The fallback lacks global limit, App Timer, Strict, Focus Cycle, Pomodoro windows and overrides. It has `isBlocked`, which A11y dropped. Each service also has its own `determineBlockedByType`, and their order differs from `shouldBlockApp`. |
| 12 | Two Hard Mode implementations | **Confirmed** | Model A is legacy PIN plus time lock, stored in the `settings` keys `hard_mode_*` with a **plaintext PIN**. It enforces nothing itself; it only sets a label and turns Strict on. Model B is the daily-limit cooldown plus phrase, stored in `global_daily_limit_settings.hardMode*`; it forces the global limit on. `repo.isHardModeEnabled()` ORs the two. |

### 0.5 New findings not in §8.3 (ranked by severity)

**R1 (critical): Timed Block does not work from the UI.**
- `TimerPickerDialog` stores `timerDurationMinutes` (`HomeScreen.kt:529`), but nothing reads it. The app picker then calls `startQuickBlock(selected)` with no duration (`:519`), so every "timed" block is open-ended.
- The only timed path is `InsightsPreviewCard`, which uses a fixed 30 min (`:500`).
- The §7 audit status "Working" is wrong.

**R2 (critical): Strict Mode pause and emergency unlock become an indefinite block.**
- `pauseStrictMode` and `emergencyUnlock` (`HomeViewModel.kt:1148,1260`) write `strict_mode_end_time="0"` but leave `strict_mode_enabled=true`.
- The service treats `endTime==0` as "active forever" (`FocusBlockAccessibilityService.kt:2400`) and never reads `strict_mode_paused`.
- It is masked only because HomeViewModel sends no cache-refresh broadcast. It takes effect on the next service (re)connect, for example after a reboot.

**R3 (critical, product rule §12.1): automatic enforcement without approval.**
- When today's social-app usage is greater than yesterday's, the service on its own:
  - inserts `BlockedApp(isBlocked=true)` rows with REPLACE, which wipes `isInAllowlist` and the counters (`:3603`)
  - turns on legacy Hard Mode
  - turns on a **2-hour Strict Mode** (`:3681-3707`)
- The once-per-day guard is in memory only (`:217`).

**R4 (high): no safety exemptions for dialler, emergency or in-call.**
- A11y's ignore list covers only systemui, `com.android.settings`, launchers, `com.samsung.android.*`, `com.sec.android.*`, gms and gsf (`:1770-1794`). Dialler, incallui and telecom are exempt only from the Strict and global checks, through `GlobalDailyLimitSettings.SYSTEM_APPS`.
- Accessibility settings is **not** exempt as such. `com.android.settings` is ignored entirely, which also means Settings can never be blocked.
- FocusBlock itself is exempt.

**R5 (high): Strict Mode does not block the apps the user picked.**
- Its scope is the global-limit tracked list, or keyword substring matching ("line", "cod", "video", …) when that list is empty (`:2799-2867`).

**R6 (high): Bedtime is never enforced.**
- It is only used to label a block (`:2082`). The §7 audit status "Working, hidden" is wrong.

**R7 (high): the widget bypasses Strict and Hard Mode** (see risk 7 in §0.4).

**R8 (high): everything uses `System.currentTimeMillis()`.**
- There are 0 uses of `elapsedRealtime`.
- A manual clock change ends Strict Lock, Quick Block, overrides and breaks early.
- There is no accessibility heartbeat.

**R9 (high): `fallbackToDestructiveMigration()` with no exported schemas.**
- Any schema bump wipes all user data.
- Phase 2 needs `exportSchema=true` plus real migrations starting from v12, and a v12 schema must be captured first.

**R10 (medium): the App Timer override returns "allow" before the Strict, Quick Block, Focus Cycle and Schedule checks (`:1869`).**
- So it unlocks apps that are also covered by stricter rules, which violates §8.2.

**R11 (medium): the App Timer "+15 min" notification action raises the daily limit permanently.**
- It does `dailyLimitMinutes += 15` (`AppTimerExtendReceiver.kt:35`) instead of granting a one-day extension.

**R12 (medium): editing the Quick Block app list starts a block.**
- `AppSelectionDialog.onConfirm` always calls `startQuickBlock` (`HomeScreen.kt:519`).
- `isEditingApps` is never read.

**R13 (medium): only one active session is not enforced.**
- `startQuickBlock` inserts without deactivating older rows. "Newest wins" only because of ORDER BY.

**R14 (medium): `runBlocking` on the main thread.**
- `AppBlockingService.kt:377`, reached through `onStartCommand`
- `SettingsViewModel.kt:327`, reached from a Compose click

**R15 (medium): stale `lastForegroundPackage`.**
- Ignored packages, including the block screen itself, never update it.
- The 10 s and 30 s loops can repeatedly re-run `blockApp` on the old app (go home, relaunch the block screen, add log rows).

**R16 (low): block-log gaps.**
- `BlockLog.scheduleId` and `scheduleName` are never set.
- `blockedCount` ("today") never resets, because `resetDailyBlockCounts` has no callers.
- There are no attempt-level or action-taken fields.

**R17 (low): there are 12 distinct override and emergency forms with inconsistent rules.**
- They are listed in the enforcement audit.
- Two are dead: `activateGlobalLimitOverride` and the A11y escalating emergency unlock.
- The Focus Cycle "Continue Anyway" changes no enforcement state.

**R18 (low): copy is hard-coded throughout.**
- There are 0 `stringResource` calls and about 400 literal strings, so §3 work means moving all of them to `strings.xml`.
- The screens use `collectAsState` 4 times and `collectAsStateWithLifecycle` 0 times.

**R19 (low): the theme is blue (`#0A84FF`) on black, dark only.**
- That is the AppBlock look §5.1 says to avoid.

### 0.6 §7 Features to protect: corrected table

| Feature | Audit status (spec) | **Verified status** | Key evidence |
|---|---|---|---|
| Quick Block | Working | **Working (open-ended only)**. It has a data-separation defect (R9 in §0.4) and the widget bypasses it (R7). | `HomeViewModel.startQuickBlock:861`, `stopQuickBlock:909` |
| Timed Block | Working | **Broken from the UI**: the duration is discarded (R1) | `HomeScreen.kt:519,529` |
| Focus/Pomodoro | Partial | **Broken**: settings are discarded and a plain block starts. The A11y work-to-break logic exists but cannot be reached. | `HomeScreen.kt:536-543`; `startPomodoroSession` has no callers |
| Scheduled routines | Working | **Working by polling only**: no alarms, `Schedule.isStrictMode` is never enforced, and logs lack the schedule id. Unverified on device. | A11y `:1972-1982`, `:1289-1380` |
| App selection | Working | **Working**. Confirming always starts a block (R12). Icons are loaded through `getInstalledApps()`; whether they load off the main thread is to be checked in Phase 4. | `Dialogs.kt:36` |
| Saved selection / last apps | Working | **Working** | `settings.quick_block_saved_apps` |
| App Groups | Backend exists, no entry | **Confirmed dead**: no UI and no insert path | `AppGroupCard.kt` has 0 refs |
| Allowlist / essential apps | Working, hidden in Setup | **Working** (Profile → Allowlist → `isInAllowlist`). There is also an `EssentialAppWhitelist` table with no UI, and a WhatsApp-only toggle in the daily-limit card. | `SettingsViewModel.updateAllowlist:180` |
| Strict Mode | Working, duplicated | **Working but defective**: pause/emergency becomes indefinite (R2), it blocks the wrong scope (R5), and its UI is duplicated on Home and Profile | see R2, R5 |
| Hard Mode | Two conflicting models | **Confirmed**. Model A enforces nothing itself and its unlock screen is unreachable. Model B forces the global limit on. | §0.4 risk 12 |
| App Timer | Working | **Working**, but the override is too broad (R10) and "+15" is permanent (R11) | A11y `:1851-1877` |
| Global daily limit | Working | **Working**. UI is duplicated on Home and Profile, and the event path ignores the whitelist. | A11y `:1817-1847` |
| Bedtime Mode | Working, hidden | **Not enforced**: UI and storage only (R6) | A11y `:2082` |
| Focus Cycles | Mostly working | **Mostly working**, accessibility service only. It is skipped while Strict is on, and "Continue Anyway" changes nothing. | A11y `:1923-1969` |
| Smart Suggestions | Partial, opaque | **Partial**, plus an **auto-enforcing** auto-block path (R3) | A11y `:3565-3707` |
| Insights / statistics | Working | **Working**, but there are two pipelines (InsightsViewModel and HomeViewModel.loadInsights) and StatisticsViewModel is dead | |
| Block-attempt history | Working | **Working**, with gaps (R16) | `BlockLog` |
| Permission setup | Fragmented | **Confirmed**: there is no onboarding step. PermissionCard is on Home, and the VM polls permissions every second. | |
| Emergency access | Several inconsistent forms | **Confirmed: 12 forms**, 2 of them dead (R17) | |
| Widget | Partial, direct DAO | **Confirmed, and it is a bypass** (R7) | |
| Reboot/session recovery | Partial | **Partial**: boot only starts the FGS. There are no alarms to restore, no time-change or package-replaced handling, and everything uses the wall clock. | `BootReceiver.kt` |
| Notifications | Working, excessive | **Confirmed**: the 15-min peak-time worker is never cancelled, plus daily insights and A11y reminders | |
| Work Mode / Digital Detox | Orphaned | **Confirmed orphaned** | |

### 0.7 §8.7 UI action → backend map (real names)

| UI action | Real entry point today | Notes |
|---|---|---|
| Start block / Repeat last | `HomeViewModel.startQuickBlock(selectedPackages: List<String>, durationMinutes: Int? = null)` | There are no strength or intention parameters |
| Start intervals | `HomeViewModel.startPomodoroSession(selectedPackages, workMinutes=25, breakMinutes=5)` | It is not called and needs repair |
| End block | `HomeViewModel.stopQuickBlock(forceStop=false): StopQuickBlockResult` | The UI ignores the result. Also `verifyPinAndStop(pin)`. |
| Add 15 min | **missing** | The only extend is `addStrictModeTime(additionalMinutes)` |
| Record outcome | **missing** | |
| Routines | `SchedulesViewModel.addSchedule(Schedule)`, `updateSchedule`, `deleteSchedule`, `toggleSchedule(id, enabled)`, `createFromTemplate(type)` | |
| App limit | `HomeViewModel.enableAppTimer(limitMinutes, packages)`, `disableAppTimer()`, `updateAppTimerLimit` (unused) | |
| Daily limit | `HomeViewModel.setGlobalDailyLimitEnabled`, `setGlobalDailyLimit`; duplicated in `SettingsViewModel` | |
| Bedtime | `HomeViewModel.toggleBedtimeMode()`, `setBedtimeTimes(sh, sm, eh, em)` | Not enforced |
| Emergency / Open anyway | 12 separate paths, no unified method | |
| Essential apps | `SettingsViewModel.updateAllowlist`; `HomeViewModel.add/removeFromEssentialWhitelist` (unused) | |
| Activity data | `InsightsViewModel` + `BlockLogDao` | |
| Widget start/stop | Direct `FocusBlockDao.insertQuickBlockSession` / `endQuickBlockSession` | Must move to the session API |

### 0.8 Open decisions touched during Phase 0 (§12.3)

None have been decided. The findings above bear on two of them:
- **Fallback parity:** the fallback currently stands down whenever A11y runs, and its `queryEvents` window only covers the last 10 s, so it is weak even as a fallback.
- **00:00–24:00 schedules:** whether `getActiveSchedules` handles overnight or all-day windows correctly must be checked by test in Phase 1.

### 0.9 Proposed Phase 1 plan (smallest safe steps, in order)

1. **Get a working build.** This needs an environment with `dl.google.com` access or a local Android Studio. Record any compile failures as pre-existing.
2. **Add a JVM unit-test source set.** Add Robolectric or pure JVM tests and Room in-memory tests, plus `exportSchema=true` with `room.schemaLocation` so the v12 schema is captured *before* any schema change.
3. **Introduce `BlockPolicyEngine.evaluate(pkg, now)` as a pure Kotlin class.**
   - It is fed by a snapshot of the existing data sources and has no Android dependencies, so it can be tested.
   - Its first version reproduces today's A11y order, so behaviour is unchanged. Tests are written against that.
   - Then it applies the §8.2 priority one overlap at a time. This fixes R10, R4 and R5, each with a test.
4. Route `FocusBlockAccessibilityService.shouldBlockApp` through the engine, and keep the service's loops in place.
5. Fix R2 (Strict pause) and R3 (auto-enforcement). For R3, disable automatic activation behind a flag and keep the code so the decision can be reviewed.
6. Add session expiry and recovery from persisted timestamps, plus `elapsedRealtime` tamper detection, plus receivers for package replaced, time and timezone changes.
7. Schedule activation with AlarmManager (exact alarms where permitted, with a fallback).

R1 (Timed Block) and R12 are UI wiring fixes and fit Phase 4. They could be fixed earlier as a tiny isolated change if you want Timed Block working sooner.
