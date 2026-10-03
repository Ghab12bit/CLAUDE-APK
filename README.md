# FocusBlock

FocusBlock helps you decide what to focus on right now, then blocks the apps that pull you away. It
also runs routines and limits on its own and keeps calls and essential apps available.

## The app

- **Quick block**: one tap blocks your apps (or your most used ones) with no timer, until you stop it.
- **Block**: start a block in seconds. Apps you use most are suggested from your recent usage.
  - Write one line about what you're working on, choose apps, then pick Timed, Intervals or
    Until I stop.
  - Optionally turn on **Strict Lock**: you can't end or shorten the block, but emergency access
    stays available.
  - "Repeat last block" starts your previous setup in one tap.
  - When a block ends, FocusBlock asks *Did you finish?*
- **Rules**: everything that runs automatically. That covers routines, app limits, a daily limit,
  bedtime (only essential apps open), Focus Cycles and essential apps. A 24-hour strip shows when
  blocking is on.
- **Activity**: what affected your focus, from your own data. Day, Week and Trend views with
  history beyond the ~7–10 days Android keeps (FocusBlock saves each day itself, for about a year).
  Apps such as a clock used as a stopwatch can be left out of screen time.
  - Screen time compared with your own 2-week average, blocked attempts by hour, apps, unlocks and
    rules that get bypassed.
  - One evidence-based suggestion. Nothing changes until you tap Apply.
- **Block screen**: opening a blocked app shows which rule blocked it and until when.
  - There is no "Open anyway": end your block (with its wait and hold) or use emergency access.
  - Emergency access needs a reason and a 10-minute wait, opens one app for 5 minutes, and is
    logged.

## How it works

- One pure-Kotlin policy engine (`app/src/main/java/com/focusblock/app/policy`) decides every
  block.
  - Both enforcement paths call it: the accessibility service, and a foreground fallback that only
    runs while accessibility is off.
  - The widget, notifications and every screen use the same session API (`core/SessionManager`).
- Room is the source of truth.
  - Blocks are stored as start and end timestamps. Nothing counts down in memory.
  - Blocks survive process death and reboots. A manual clock change can't end a Strict Lock block
    early within the same boot.
- Database version 17 keeps every earlier table. Migrations from versions 12–16 never drop data.

See `docs/implementation-log.md` for the architecture, decisions and verification evidence, and
`docs/metrics.md` for how every number is calculated.

## Install

Every green build on `claude/new-session-hf7zu9` that is marked for release is published as the
**focusblock-latest** GitHub prerelease. Download `FocusBlock-debug.apk` from that release.

- Builds are signed with the repository's public development key, so later builds install over each
  other.
- A build signed with a different key (for example, one built in Android Studio with its default
  debug key) can't update in place.

After installing, the first-run setup asks for these, one at a time:

| Permission | Why |
|---|---|
| Accessibility | Required. It's how FocusBlock notices a blocked app. It only receives window changes and can't read window content. |
| Usage access | Limits and Activity |
| Notifications | Block status and "Did you finish?" |
| Unrestricted battery | Reliability. On Samsung, also keep FocusBlock out of Sleeping apps. |
| Exact alarms | Ends blocks and asks "Did you finish?" on time |

## Build

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Requires JDK 17 and Android SDK 34. GitHub Actions (`.github/workflows/android-recovery.yml`) runs:
- unit tests
- lint
- a debug build
- emulator tests: enforcement end to end, Strict Lock bypass rules and Room migrations
