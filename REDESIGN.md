# FocusBlock redesign — what changed, what's tested, what isn't

This document separates three things deliberately: what is **implemented**, what is
**verified by an automated test**, and what **still needs checking on a physical
phone**. Compiling is not enforcement, and a green build says nothing about whether
your phone's battery manager kills a service at 2am.

---

## 1. Why the app needed rebuilding, not restyling

The starting point had nine parallel blocking sources — Quick Block, Schedules, App
Timer, Global Daily Limit, Focus Cycle, Bedtime Mode, Strict Mode, Hard Mode and App
Groups. Each carried its own app list, its own detection logic and its own bypass
rules. Reading the enforcement path turned up ten defects, several of which explain
why the app felt untrustworthy:

| # | Defect | Where |
|---|---|---|
| 1 | **Pomodoro was inverted.** It *unblocked* distracting apps for the 25-minute "focus" period and blocked them during the break. It also returned early, silently suppressing any active schedule. | `HomeViewModel.kt:1027`, `FocusBlockAccessibilityService.kt:1905-1913` |
| 2 | **Bedtime Mode never blocked anything.** It was absent from `shouldBlockApp()` and appeared only in `determineBlockedByType()`, which runs *after* a block is decided, purely to label it. The home-screen toggle was decorative. | `:1796-1996` vs `:2082` |
| 3 | **Two divergent rule engines.** Which rules applied depended on which service happened to be alive. The fallback service had no Strict Mode, no budgets, no Focus Cycle, no Bedtime — and honoured an `isBlocked` flag the other path deliberately ignored. | `FocusBlockAccessibilityService.shouldBlockApp():1796` vs `AppBlockingService.shouldBlockApp():155` |
| 4 | **`UnifiedBlockingManager` was dead code.** It declared the consolidation but had zero references. | — |
| 5 | **Strict Mode ignored your app list** and blocked by keyword category. The keyword list contained `whatsapp`, `signal`, `telegram`. | `:2799` → `:2821-2839` |
| 6 | **Two unrelated Hard Modes.** Settings wrote one flag; enforcement read the other. The status chip could claim "Hard Mode Active" while nothing enforced it. | `GlobalDailyLimitSettings.isHardModeEnabled` vs `AppSettings.KEY_HARD_MODE_ENABLED` |
| 7 | **Every schema change wiped all user data.** | `FocusBlockDatabase.kt:91` |
| 8 | **Ending one thing ended everything.** The Hard Mode PIN unlock disabled Strict Mode *and* killed all Quick Block sessions in one handler. | `HardModeUnlockActivity.kt:222-234` |
| 9 | **Overnight schedules died at midnight** unless the following day was also ticked. | `FocusBlockDao.kt:82-95` |
| 10 | **Timed blocks were not durable.** Expiry relied on a 1-second `Handler` inside the accessibility service; if it died, an expired session stayed active and apps stayed blocked with no way to clear them. | `:388-490` |

Two further defects were found auditing the permission surface, and two more in code
written for this redesign — all listed in §4.

---

## 2. The new model

Modelled on [AppBlock](https://appblock.app), whose architecture solves exactly the
"overlapping features" problem. Nine blocking sources collapse into **one object**:

```
a rule  =  a set of apps  ×  a set of conditions   (all conditions must hold)
```

| Old feature | Becomes |
|---|---|
| Quick Block | manual rule, optional expiry |
| Schedules | rule + Time condition |
| Bedtime Mode | rule + Time condition crossing midnight |
| Daily App Timer | rule + Usage condition (daily) |
| Daily Screen Limit | rule + Usage condition (daily), wider app set |
| Focus Cycle | rule + Usage condition (**hourly**) — "10 minutes an hour" |
| App Groups | a saved app selection, not a blocking source |
| Strict + Hard Mode | `ProtectionLock` — a lock on *configuration*, not a blocker |
| Pomodoro | removed (it was a work timer, and it was backwards) |

Focus Cycle is the instructive case: its entire armed/paused state machine plus a
floating overlay service is exactly an hourly budget. The behaviour survives; ~500
lines of machinery does not.

### Two invariants enforced in code

1. **Rules are additive.** A rule can only ever *add* a block. Nothing can un-block an
   app another active rule is blocking. This makes defect #1 structurally impossible
   rather than merely fixed.
2. **The allowlist beats everything**, including a PIN-locked rule. Your phone stays
   usable and you stay reachable.

### Ending a rule

Scoped to one rule, always. `BlockingEngine.requestEndRule()` returns the names of
rules that keep blocking afterwards, and both the home screen and the block screen
show them, so "I stopped it but it's still blocked" never happens without an
explanation.

---

## 3. Implemented

**Engine and data**
- `BlockRule` / `BlockingEngine` — one rule set, called by both services.
- `ProtectionLock` — one commitment layer (Off / Locked / PIN-locked), replacing
  Strict Mode and both Hard Modes. Terms are shown before it arms; it is never raised
  automatically.
- Real Room migrations. `fallbackToDestructiveMigration()` is gone. Migration 12→13
  translates every old blocking source into rules, promotes the old "tracked but not
  blocked" whitelist into the real allowlist, carries an in-flight Quick Block across,
  and leaves every old table untouched.
- Durable expiry via `AlarmManager`, re-armed on boot, on app update, and whenever the
  accessibility service connects. Expiry is a property of the data, so a missed alarm
  can delay a UI refresh but can never leave an app wrongly blocked.
- PIN salted and hashed (was plaintext), constant-time comparison.

**Interface**
- **Home** answers four questions in order: is blocking working / what's blocked, why,
  until when / what starts next / how do I start or adjust. Blocked apps are grouped
  **by rule**, so overlap is visible. No goal field, no intention prompt, no reflection.
- **Protection card** reports real signals — accessibility connected, usage access,
  overlay, battery — rather than a badge derived from a flag.
- **Routines** — create, edit and arm rules; everything on one sheet.
- **Setup** — fresh install to one working routine in about a minute. Apps are
  pre-ticked from your own last-week usage; communication apps are labelled, never
  pre-ticked, and any you leave unticked are written to the real allowlist.
- **Block screen** names the routine and when it lifts, and states overlap.
- **Settings** shows only what still drives behaviour: the five permissions that
  decide whether blocking can happen (including exact alarms), the allowlist, and the
  protection lock. Two deliberate asymmetries — allowlisting is permitted even while
  locked (a safety valve a commitment can remove is not a safety valve), and the lock
  can be extended but never shortened while it holds.
- **Insights** keeps the real screen-time data and adds *protected windows*: minutes
  of a routine's blocked apps used inside that routine's own window, and how many
  times the block screen appeared. No score, grade or streak is derived from it.

**Removed** — about 17,500 lines
- The 6,204-line `HomeScreen` and its ViewModel, the old Settings and Schedules
  screens, the onboarding flow.
- Focus Cycle state machine and overlay service, App Timer polling and its reflection
  activity, Pomodoro, `UnifiedBlockingManager`, auto-block-on-excess-social.
- Six notification systems: session reminders, timer warnings, limit warnings, 3-hour
  nags, daily comparisons, peak-time reminders.
- `HardModeUnlockActivity` — the PIN screen that disabled Strict Mode *and* killed
  every Quick Block session in one handler (defect #8).

---

## 4. Defects found and fixed during this work

Beyond the ten above:

- **`android:packageNames=""`** in the accessibility config. An empty package list can
  be read as *"receive events from no packages"* — which would stop the service seeing
  app launches entirely. Attribute removed; `notificationTimeout` dropped to 0.
- **`POST_NOTIFICATIONS` was never requested at runtime.** Declared and checked, never
  asked for. On Android 13+ the foreground-service notification is silently
  suppressed: blocking runs, but you lose the only persistent signal that it is.
- **The allowlist migration silently didn't work.** It compared each package against
  the *whole* comma-separated whitelist via `IN (...)`, so it would only ever have
  matched a single-app whitelist. This is the WhatsApp-stays-reachable path.
- **The engine hit the database on every foreground change**, on the path where
  latency is most visible. Rules and allowlist now cached 2s, invalidated on write.
  Usage counters are never cached, so a spent budget still blocks on the next open.
- **Legacy notification workers were already enqueued on-device.** Both used
  `enqueueUniquePeriodicWork(..., KEEP)`, so WorkManager persists them across app
  updates; not scheduling them was insufficient. They are cancelled by name. One of
  them fired a screen-time summary **at 9pm daily** — inside the evening work block.

---

## 5. Tested

Automated, on every push. CI: `.github/workflows/android.yml`.

**JVM unit tests** — 24 tests over rule logic:
- Same-day windows: start inclusive, end exclusive, inactive outside, day filtering.
- Overnight windows: evening part, morning part, midday inactive.
- **Regression:** a Mon–Fri 22:00–06:00 rule still blocks at 01:00 Saturday (the tail
  of Friday's window), and does *not* start on Saturday evening.
- Sunday→Monday wraparound; Monday-morning tail correctly requires Sunday.
- Manual expiry as a property of data, including "expired" and "indefinite".
- Package coverage: exact-match only; no prefix false positives.

**Instrumented tests on an Android emulator** — migration 12→13 against real SQLite:
- Runs without error on an empty database.
- Schedules → rules with a time condition; a strict schedule → `LOCKED`.
- Bedtime → overnight rule with correct day string (Mon/Wed/Fri verified).
- Focus Cycle → hourly budget.
- An active Quick Block survives the upgrade.
- Strict Mode → locked `ProtectionLock`; no commitment → `OFF`.
- **Regression:** every whitelisted app is promoted to the allowlist, not just one.
- **Schema-compatibility check:** every table the migration hand-writes is compared
  column-for-column against the table Room generates for the same entity. A mismatch
  is precisely the exception Room throws on open — this is the check that prevents a
  crash-on-launch for an upgrading user.

**Build** — debug APK produced and uploaded as a CI artifact on every green run.

---

## 6. Not tested — please verify on the phone

None of this is knowable from CI. Listed in the order most likely to bite.

1. **Does blocking actually fire?** Open a blocked app during a routine's window. The
   block screen should appear and name the routine and end time.
2. **Overnight survival.** Leave a routine running past its end time overnight. Does it
   lift on time? OEM battery managers (Xiaomi/MIUI, Samsung, Oppo) aggressively kill
   accessibility services — the single most likely reason a blocker quietly stops
   working. Exclude FocusBlock from battery optimisation.
3. **Exact alarms on Android 14+.** `SCHEDULE_EXACT_ALARM` is not auto-granted at
   targetSdk 34. The scheduler degrades to an inexact one-minute window rather than
   crashing, so a block may start up to a minute late. Not yet surfaced in the UI.
4. **Reboot.** Restart the phone mid-routine; blocking should resume and the next
   boundary re-arm.
5. **Permission loss.** Turn the accessibility service off. The home screen must say
   "Blocking is not working" rather than showing a green badge.
6. **Essential-app access.** With a locked routine running, confirm WhatsApp, phone and
   messages are all still reachable.
7. **Overlap.** Create two routines covering the same app; stop one; confirm the app
   stays blocked and the app tells you why.
8. **Usage budgets.** Set a 5-minute daily budget, spend it, confirm the block lands on
   the next open.

---

## 7. Known limitations

- **Old tables are retained.** The migration reads them and leaves them in place, so a
  translation bug cannot cost data. They can be dropped once the model is trusted.
- **No test covers the accessibility service end to end.** That needs a device, and it
  is the component everything else depends on.
- **The protected-windows figure is not attributed.** It reports minutes used inside a
  window without distinguishing an allowlisted app from a block that failed. Honest,
  but blunt.
- **Overnight windows are measured only to midnight** in the protected-windows view.
  Enforcement handles them correctly; the reporting does not yet stitch the two halves.
- **Widget still reflects the old model** and has not been reviewed against rules.
- **No usage-condition rule is created by setup.** Only the evening window is; daily
  budgets and hourly limits are available as templates in Routines.

---

## 8. What this app does not claim

It blocks apps you choose at times you choose. It does not repair attention, reset
dopamine or guarantee an improved attention span. The mechanism is friction and
pre-commitment: a decision made at a calm moment is harder to reverse at a tempting
one. That is the entire theory of operation.
