> **Superseded (2026-10-02).** This describes the recovery branch's interim design. The current
> architecture, decisions and evidence are in [implementation-log.md](implementation-log.md).

# FocusBlock blocking-first implementation

## Design source

The locked 1 October 2026 design supersedes the earlier task-led Focus/Guardrails/Patterns prototype. Native navigation is **Block / Rules / Activity**, with ink-and-paper surfaces and a restrained copper accent. No WebView or prototype statistics are included.

## What changed

- Block: integrated installed-app icon picker, saved selection, repeat previous setup, timed/cycle/until-stopped sessions, scoped Strict Lock, active session, extension and completion.
- Rules: recurring time windows, bedtime/morning presets and per-app daily allowances. Overnight ownership follows the day on which a window starts.
- Activity: actual UsageEvents in comparable today/yesterday windows, chosen/all-app scope, app usage, block history and confirmation before setting a suggested limit.
- Intervention: immediate Home exit, optional bounded session access, optional offline reminder. Existing App Timer/daily-limit allowances remain separate and cannot override a session or schedule.
- Setup: native permission explanations and recovery; real app icons; essential apps remain accessible.
- Existing advanced controls remain reachable for Hard Mode, legacy Strict Mode, shared App Timer, usage-triggered Focus Cycles, global daily limit, smart suggestions, app groups and notifications.

## Reused architecture

The existing application ID, Room tables/DAOs, accessibility service, foreground fallback, boot receiver, widget and legacy ViewModels are retained. New session metadata is stored in the existing settings table. The interface does not own timer enforcement.

Quick-block selection stays separate from permanent blocked-app settings. Finishing one session does not stop other policies. A session exception is package-scoped, bounded by the session end, transactionally spent once and persisted across process recreation.

Database version 16 preserves owned version-12 tables and additional version-13–15 tables. Imported rule rows retain combined time/usage/launch conditions through a read-through adapter. Their original configuration locks are respected. No destructive fallback is enabled.

## Repairs

- Fixed baseline Compose compilation against the repository's actual Material3 version.
- Fixed Android lint errors for receiver registration, back handling and API-26 theme attributes.
- Restored per-app limit enforcement through both monitoring paths.
- Prevented an expired quick block or a timer exception from skipping unrelated policies.
- Removed blanket Samsung package exemptions from explicit blocking.
- Preserved previous-foreground information when updating usage-cycle tracking.
- Made concurrent session-access requests atomic and kept the widget subject to locks.
- Refresh blocked-app activity content when Android reuses its existing activity.
- Removed synchronous database work from foreground-notification creation.
- Preserved foreground monitoring when a quick block ends; restart monitoring on sticky-service recreation.
- Reused the repository's public development signing key for repeatable debug updates.

## Validation boundaries

GitHub Actions runs JVM tests, APK compilation, Android lint and Android-14 emulator instrumentation. Instrumentation checks concurrent access spending, zero-budget enforcement, preservation migrations, legacy timer access, native navigation, persisted active/completion states and opening an installed app during a session. CI reports are the source of truth for pass/fail status.

Migration fixtures exercise version transitions and preserved payloads; they are not a copy of the owner's phone database. Real S23 Ultra reboot, Samsung battery-management behavior, Android upgrades, extended overnight use and accessibility re-enablement still require that device. A successful build cannot establish the absence of all bugs.

## Remaining platform and compatibility limits

- This is a debug APK, not a Play Store release. An installed APK signed with another key will reject an in-place update. Do not uninstall to bypass that error without preserving existing data.
- Imported combined rules remain enforced and can be edited when unlocked; their original commitment level and manual activation deadline are preserved rather than silently reset by the new editor.
- Some advanced controls retain legacy presentation. They are not silently deleted or presented as newly device-verified.
- Database versions older than 12 do not have a new migration in this change. Opening an unsupported schema fails rather than wiping data.
- Revoking accessibility/usage access, force-stopping, Safe Mode or device power management can interrupt blocking. Strict Lock is an in-app policy, not uninstall-proof device management.
- UsageEvents can be unavailable or incomplete. Activity reports unavailable data explicitly and does not infer concentration or assign a clinical score.

## S23 Ultra acceptance checks

1. Install over a matching-signed prior build; confirm selections, schedules, limits and history survive.
2. Enable accessibility, usage access and overlay permission; check notification and Samsung sleeping-app settings.
3. Select installed apps, block for a short duration and attempt to open each app. Calls/Home/essentials must remain reachable.
4. Try Strict Lock from the app, widget and advanced controls; attempts to weaken the session must fail.
5. Spend a one-time exception; confirm the same app is reblocked at expiry and a second exception is refused. Unrelated schedules must continue.
6. Check cycle boundaries with the selected app foregrounded; verify other rules during breaks.
7. Check a schedule crossing midnight, a daily limit, timezone/date changes and permission loss/recovery.
8. Restart the phone during a session and confirm the original absolute deadline and spent access budget remain.
