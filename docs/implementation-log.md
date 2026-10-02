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

---

## Phases 1–8 — Build (2026-10-02)

The owner authorised every phase, commits and pushes, and asked for the final app. Phase reports
are therefore combined here instead of pausing after each phase. Each slice was still pushed and
checked by CI on its own.

### Base and environment

- **Base.** The work builds on the recovery line `codex/blocking-first-20261001` (`94a12aa`), which
  the owner chose.
  - Its v16 preserving migrations, fixed signing key and emulator CI were kept.
  - Its interim UI was replaced.
- **Build environment.** This cloud container cannot reach `dl.google.com`, so Android cannot be
  compiled here.
  - Every Android build, lint run and emulator test runs in GitHub Actions
    (`.github/workflows/android-recovery.yml`).
  - The pure-Kotlin policy core is also compiled and tested locally with a JVM-only Gradle build.
- **Device testing.** No physical device is available to this session. The real-device matrix
  (§11.3) is **not** marked as passed; see "Known limitations".

### Architecture (what exists now)

| Layer | Files | Notes |
|---|---|---|
| Policy (pure Kotlin) | `policy/*` | `BlockPolicyEngine.evaluate(pkg, snapshot, now, zone)` is the single decision. It also covers priority (§8.2), session phase math, time windows (overnight, DST, time zones), friction, usage accounting, coverage, metrics and recommendations. |
| Runtime | `core/*` | `SessionManager` is the only session API, used by the UI, widget, notifications and recovery. `PolicyRepository` builds snapshots from Room. `Enforcer` decides and logs, and both services call it. Also: `OverrideManager`, `FocusCycleTracker`, `AlarmScheduler`, the receivers, `Notifier`, `PermissionHealth` and `AppGraph` (wiring). |
| Enforcement | `service/FocusBlockAccessibilityService` | Primary path. Window-change events only; window content is never read. It holds no policy and re-checks at the next policy boundary or limit run-out. |
| | `service/AppBlockingService` | Fallback. Runs only while Accessibility is not running and something needs enforcing. Same `Enforcer`, so the same policy set. |
| Data | Room v17 | One table per concept (§8.4): `block_sessions`, `essential_apps`, `app_limits`, `unlock_events`, `recommendations`, `diagnostic_events`. Legacy tables are kept and never dropped. |
| UI | `ui/*` | Block, Rules and Activity tabs; Settings and its sub-screens; app picker sheet; rule editor; intervention activity; first run; tokens and primitives. |

### Final policy priority (§8.2), as implemented and tested

1. Safety exemptions and essential apps:
   - FocusBlock itself
   - dialler, in-call, telecom and emergency apps, and cell broadcasts
   - System UI and Settings, which includes accessibility settings
   - permission controller and package installer
   - home launchers and keyboards
   - the user's essential apps
2. A valid emergency override for that app (time-boxed).
3. Strict Lock reasons: a Strict block, a Strict routine, then any other Strict reason (Strict
   bedtime; a daily limit under a still-active legacy Hard Mode lock).
4. Bedtime (allow-only: every user-facing app except essentials).
5. Routine (including imported v13–15 rules).
6. App limit.
7. Daily limit.
8. Focus Cycle break.
9. Normal immediate block.

Rules for overrides and display:
- "Open anyway" overrides are honoured only when no Strict reason applies, and are refused at grant
  time otherwise.
- The highest reason decides the copy and the bypasses. The rest are listed as "Also blocked by".
- Tests: `BlockPolicyEngineTest.everyOverlapPairResolvesToTheHigherPriorityReason` covers all 27
  pairs, plus targeted tests for overrides, exemptions, intervals, limits and the trusted clock.

### Strict/Hard migration mapping (§8.5)

The migration is `Migration16To17`, tested in `Migration17Test` with legacy fixtures.

| Legacy state | Becomes |
|---|---|
| Strict Mode on, future end time, not paused | Strict Lock until the original end time. A running block becomes Strict (an open-ended one gets that end time). With no block running, a Strict block of the daily-limit apps runs until the original end. |
| Strict Mode on with end time 0, or paused (the R2 bug state) | Not carried over; recorded in `diagnostic_events` and `settings.migration_17_report`. |
| Hard Mode A (PIN + time lock) | Default block strength becomes Strict Lock. **The plaintext PIN is deleted.** |
| Hard Mode B (daily-limit cooldown/phrase) | `hardModeLockUntil` is kept. While it is in the future, the daily limit is enforced as Strict Lock and can't be turned off. |
| Recovery session metadata (strict, cycles, one-time exception) | Applied to the migrated active block. An unexpired exception becomes a granted override until its original expiry. |
| `blocked_apps.isInAllowlist` and `essential_apps_whitelist` | `essential_apps` |
| `app_time_limits` rows and the `app_timer_settings` list | `app_limits` rows |
| Global limit tracked set (tracked ∪ App Timer apps when shared − "tracked but not blocked") | An explicit counted list. When empty, the default distracting apps. |

### Decisions taken (open decisions §12.3), safest temporary behaviour — please confirm

| # | Decision | Chosen for now |
|---|---|---|
| 1 | Merge Focus Cycles into Intervals? | Kept separate; shown under Rules › Focus Cycles. The floating overlay timer was dropped; the state shows on the rule row. |
| 2 | App limit vs Daily limit | App limit = shared allowance for chosen apps (any number of them). Daily limit = one total allowance, counting either all apps except essentials or a chosen list. They are separate in storage and code paths. |
| 3 | Fallback parity | Full decision parity, because it uses the same `Enforcer`. Detection is best-effort (about 1 s, screen on), and it needs "Display over other apps" to show the block screen. |
| 4 | 00:00–24:00 | `start == end` means a 24-hour window starting at that time; 00:00–00:00 is all day. The editor has an "All day" switch. |
| 5 | What the widget starts | The last block by default (it always has an end time if it was timed); can be switched to the default block in Settings. |
| 6 | Time saved | Not shown. |
| 7 | App Groups | User-created saved sets only; they show in the picker and in Settings › Saved app sets. |
| 8 | Work Mode / Digital Detox | Converted to rule templates (Work hours, Weekend detox, Evening routine, Slow morning). The orphaned dialogs were removed. |
| 9 | Signature visual | Filling bars, taken from the brand mark: the tall bar fills with progress and the accent bar shows focus or break. |
| 10 | Emergency wait / unlock length | 10 min wait, 5 min unlock. Open anyway is also 5 min. |

Other choices:
- Bedtime is allow-only: only essential and safety apps open. It applies only to apps with a
  launcher icon.
- Default essentials, seeded once: phone, messages, clock, camera, maps.
- "Add 15 minutes" on the end sheet records `EXTENDED` and starts a new 15-minute block with the
  same apps, strength and intention.
- Charging-time insights and the 15-minute "peak time" worker were removed. They did not serve the
  product loop and were noisy (spec 7 "Notifications: excessive"). Usage reminders (30/60 min) and
  the daily summary remain, each with a switch.

### §6 mockup fixes

All 17 are done. Where an item is covered by an emulator test, the test is named.

- [x] Bottom bar reads Block / Rules / Activity everywhere. (`EndToEndTest.tabsRenderFromPersistedState`)
- [x] One verb: blocked copy only; no "paused" or "protected" in `strings.xml`.
- [x] Strict intervention: no "Need access?". Only low-emphasis "Emergency access" with
  "10-min wait · reason required". (`strictLockShowsOnlyEmergencyAccessAndCannotBeEnded`)
- [x] Strict active screen: no temporary-access row.
- [x] "You can't end this block early" replaces "cannot be stopped".
- [x] "Blocked by {rule}" replaces "You chose to block this app".
- [x] "Put your phone aside" removed. Strict active screen has Add 15 minutes only.
- [x] Cycles → Intervals; Until stopped → Until I stop. (`idleBlockTabShowsSetupWithoutGiantNumber`)
- [x] Intention field on setup; shown on the active screen and the intervention.
- [x] Attempt counter and progressive friction on the intervention.
- [x] Session end sheet: Finished / Not yet / Add 15 minutes.
- [x] No giant duration number; the chip and the button carry it.
- [x] Repeat last block: whole row tappable, play icon at the right edge.
- [x] Essential apps row reads "Available" on one line (`maxLines = 1`, value never wraps).
- [x] Active title is the end time; the countdown is secondary.
- [x] Signature active visual (`ActiveBlockVisual`).
- [x] Insets: one Scaffold applies system-bar padding, the bottom bar adds navigation-bar padding,
  and content uses IME padding.

### §7 parity table: every feature's new home

| Feature | New home | Status |
|---|---|---|
| Quick Block | Block tab (Until I stop, Repeat last block) | Rebuilt on `SessionManager` |
| Timed Block | Block › Timed (25/45/60/Custom) | Rebuilt; the duration bug (R1) is gone |
| Focus/Pomodoro | Block › Intervals | Rebuilt; phases come from the clock; breaks unblock unless a rule covers |
| Scheduled routines | Rules › Routines | Engine plus next-change alarm; Strict routines locked while active |
| App selection | App picker sheet | Contextual, searchable, real icons |
| Saved selection / last apps | Repeat last block, picker "Recent" | Kept (`blocking_first_last_v1` key reused) |
| App Groups | Picker "Saved sets", Settings › Saved app sets | Connected |
| Allowlist / essential apps | Settings › Essential apps; greyed in the picker | Migrated to its own table |
| Strict Mode | Strict Lock strength | Migrated (see above) |
| Hard Mode A / B | Strict Lock / daily-limit lock | Migrated; PIN deleted |
| App Timer | Rules › Limits › App limit | Migrated |
| Global daily limit | Rules › Limits › Daily limit | Kept; explicit counted list |
| Bedtime Mode | Rules › Bedtime | **Now enforced** (R6) |
| Focus Cycles | Rules › Focus Cycles | Kept; same engine |
| Smart Suggestions | Activity › one suggestion | Rebuilt; approval required; the auto-enforcing path (R3) is gone |
| Insights / statistics | Activity | Rebuilt from logged data; formulas in `docs/metrics.md` |
| Block-attempt history | `block_logs` with attempt number and action | Extended |
| Permission setup | First run, Settings › Blocking health | Rebuilt |
| Emergency access | Intervention (one flow) | Standardised |
| Widget | Widget on the session API | Rebuilt; respects Strict Lock |
| Reboot/session recovery | `SystemEventReceiver` | Boot, update, time and time-zone changes |
| Notifications | Settings › Notifications | One screen, five switches, separate channels |
| Imported v13–15 rules | Rules › "Rules from the previous version" | Enforced through the engine; editable when unlocked |
| Work Mode / Digital Detox | Rule templates | Converted |

### Verification evidence

Android cannot be built in the authoring container: `dl.google.com` is blocked there. All Android
evidence therefore comes from GitHub Actions (`.github/workflows/android-recovery.yml`) on this
branch. Only the pure policy package was also compiled and tested locally, as a plain JVM project.

| Check | Where | Result |
|---|---|---|
| `assembleDebug` | CI `build` | Pass |
| Unit tests, 66 in total: policy engine and every §8.2 overlap pair, time windows (overnight, DST, time zone), sessions and friction, usage, coverage, recommendations, migration SQL against Room's schema 17, colour contrast (WCAG AA) | CI `build` (and policy tests locally) | Pass |
| `lintDebug` | CI `build` | Pass |
| Room migrations 12–16 → 17 with legacy fixtures, including Strict and Hard Mode mapping (`Migration17Test`, 3 tests) | CI `emulator`, Android 14 | Pass |
| End to end with the real accessibility service (`EndToEndTest`, 5 tests). Opening a blocked app shows the block screen and is logged; Strict Lock offers only emergency access and cannot be ended; Open anyway is time-boxed and logged; tabs render from persisted state; the idle Block tab shows setup | CI `emulator`, Android 14 | Pass |

Final run: GitHub Actions run 36993637732 on commit `e5f0434`. Build, unit tests and lint passed, and
all 8 emulator tests passed (5 end to end and 3 migration). The `release` job published
`FocusBlock-debug.apk` as the `focusblock-latest` prerelease.

Bugs found by the emulator runs and fixed:
- Compose crashed after Open anyway, because of an early `return@Column` after conditional
  composition. All inline-layout early returns were removed.
- Tests re-applied the accessibility setting before each test. Android then re-bound the service
  partway through the test, and window events were lost.
- When Android re-binds the accessibility service, the old instance can be destroyed after the new
  one connects. The old instance then marked the service as disconnected, which showed a false
  "Accessibility is off" banner and started the fallback service. Only the connected instance can
  now report a disconnect.
- The accessibility service pressed HOME and then started the block screen. Android handles HOME
  asynchronously, so the launcher could land on top and hide the block screen: the blocked app
  closed, but without the reason or any choices. The service now starts the block screen directly
  over the blocked app, like the fallback service. It presses HOME only if that start fails, or if
  the blocked app is still in front 1.5 s later (Android can refuse a background start silently).
- UiAutomator's default connection suspends every other accessibility service. The tests now keep
  FocusBlock's service running, so they exercise the real enforcement path throughout.

### Known limitations (not verified, or by design)

- **No real-device test yet.** Nothing has been run on a Samsung S23 Ultra or on any other physical
  phone. One UI's battery management ("Sleeping apps") and its accessibility-service killing are
  real risks. Blocking health detects them and says so, but only a device run proves enforcement.
  The device matrix (other OEMs, Android 10–13) is unknown.
- **Fallback enforcement is weaker.** Without accessibility, the foreground service polls usage
  events every second. A blocked app can be visible for about a second, and Android can delay
  usage events.
- **Strict Lock is not uninstall-proof.** Someone can still disable accessibility, force-stop the
  app or uninstall it. Device-admin or uninstall protection was out of scope, because it needs a
  decision on 12.3. Turning accessibility off is detected: the protection notification and the
  fallback service start.
- **The clock-tamper guard holds within one boot.** Blocks are timed with elapsed realtime and the
  boot count. After a reboot, the wall clock is trusted again, so a clock moved forward and then
  rebooted can end a Strict block early.
- **Usage-based numbers depend on Android.** When Usage access is missing they show as unavailable,
  never as zero.
