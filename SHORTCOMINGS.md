# FocusBlock — shortcomings, and the prompt to fix them

Everything still wrong with the app, in priority order. Written to be handed to
whoever works on it next, including me.

Three categories are kept strictly apart, because conflating them is how the
last few hours got wasted:

- **BROKEN** — verified wrong in the code.
- **UNVERIFIED** — cannot be settled without a physical phone. Not the same as working.
- **MISSING** — never built.

---

## The goal, so priorities can be argued against something

One user. Home from the gym ~8pm. Wants **8:45–10:30pm on weeknights** protected
for client outreach and building. Screen time has run ~6h/day, mostly Facebook,
Reddit, YouTube, Instagram. Needs WhatsApp and WhatsApp Business always
reachable. Abandoned his own blocker before because he could not tell what its
settings were doing.

**A fix earns its priority by how much it increases the chance that 8:45pm on
Monday actually works, and that he can tell it worked.** Everything else is
secondary.

---

# P0 — the app may not do its core job

## P0.1 Blocking has never been confirmed to fire on a real device

**UNVERIFIED.** Every claim of correctness rests on unit tests of rule logic and
an emulator migration test. No test has ever observed the accessibility service
actually intercept an app launch.

- Why it matters: if this is wrong, nothing else in this document matters.
- Fix: on device, set a routine's window to the current minute, open Instagram,
  confirm the block screen appears naming the routine and end time. Then write
  an instrumented test that drives the same path.

## P0.2 Nothing detects or reports enforcement silently stopping

**BROKEN.** If an OEM battery manager kills the accessibility service at 2am,
the app keeps showing its last state. There is no heartbeat, no detection, and
no notification. The user finds out by discovering Instagram open.

- Why it matters: this is the single most common way a blocker dies, and the app
  currently lies about it by omission.
- Fix: record a timestamp on every accessibility event. If the service has not
  reported within N minutes while a routine window is active, post a notification
  ("Blocking stopped — tap to fix") and show it on the home hero. `WorkManager`
  periodic check is enough; it does not need to be precise.

## P0.3 Overnight survival is unknown

**UNVERIFIED.** Whether the service survives a night on this specific phone,
with its specific battery manager, has never been observed.

- Fix: leave a routine running overnight. Check in the morning whether the block
  screen still appears. If not, the answer is OEM-specific autostart settings,
  and the app must walk the user through them by manufacturer.

## P0.4 Exact alarms silently degrade on Android 14

**BROKEN (partially).** `SCHEDULE_EXACT_ALARM` is not auto-granted at targetSdk
34. The scheduler falls back to `setWindow` with a 60s tolerance, which is
correct behaviour — but a routine can then start or lift up to a minute late and
the user is told only in a settings row they may never open.

- Fix: surface it on the home hero when a routine is armed and the permission is
  missing. One line: "Routines may start up to a minute late."

---

# P1 — user-visible failure or confusion

## P1.1 Permissions are five separate trips to five system screens

**MISSING.** The highest-value remaining work, and the thing the user asked for
by name.

Currently: a list of five rows, each with a "Fix" link that throws the user into
a different Android settings screen with no guidance about what to tap, no sense
of progress, and no return path.

Build instead a **single sequential flow**, one permission per full screen:

- Progress dots across the top, filled blue for granted.
- Headline in plain language at 30sp: "Let FocusBlock see which app is open."
- One sentence on why, and what breaks without it.
- **A diagram or screenshot of the system toggle being hunted for.** Every OEM
  buries these differently; this is the difference between granting it and
  giving up.
- **Auto-advance on return.** When the user comes back having granted it, move to
  the next step without them having to find it.
- Ordered by importance: accessibility → usage access → battery → overlay →
  exact alarms. Say plainly that blocking works after the first two, and let
  them stop there.
- Ends with a confirmation screen, not silence.
- Re-entrant from Setup, resuming where it left off.

## P1.2 Blocked apps are a comma-separated string, not icons

**MISSING.** The home screen renders "Facebook, Instagram, Reddit, YouTube" as
text. Icons are recognised instantly; names are read slowly.

- Fix: an `AppIconGrid` component — launcher icons at ~44dp, blue ring when
  selected. Use it on the home screen, in the routine editor, and in setup.
  This is probably the single highest-impact visual change left.

## P1.3 The idle state says nothing useful

**BROKEN.** Outside a routine's window — which is most of the day — the home
screen says "Nothing blocked" and stops. It does not say when the next routine
starts or how long until then.

- Fix: `STANDING BY / Nothing blocked / Evening work block starts in 6h 12m`,
  with a live countdown. The user should be able to open the app at 3pm and
  understand their evening is already arranged.

## P1.4 Routine cards are dense text

**MISSING.** The reference uses profile cards: a small circular coloured icon, a
bold name, one small subtitle, a toggle. FocusBlock stacks lines of similar
weight and expects them to be read.

- Fix: rebuild the routine card to that shape. At rest it should be legible in
  under a second without parsing text.

## P1.5 The routine editor is a cramped bottom sheet with −/+ steppers

**MISSING.** Setting 8:45pm takes repeated taps on a small button inside a
scrolling sheet, inside another scrolling list.

- Fix: full screen, not a sheet. Time set with a dual-handle control over a
  24-hour track so the window is visible as a shape. Steppers remain as fine
  adjustment, not the primary mechanism.

## P1.6 Nothing confirms anything

**MISSING.** Finishing setup, a routine starting, a block ending — all pass
without acknowledgement. The app never says "that worked."

- Fix: a confirmation state at the end of setup, and a brief notification when a
  routine begins ("Evening work block started — 7 apps blocked until 10:30pm").

## P1.7 No way to pause or skip a routine tonight

**MISSING.** A routine is on or deleted. There is no "skip tonight" or "start 30
minutes late", so the only way to handle an exception is to weaken the rule
permanently — exactly the wrong affordance.

- Fix: "Skip tonight" on the routine card and in the pre-start notification.
  Logged, one-off, does not alter the rule.

---

# P2 — parity with the reference, where it actually matters

Judged against the user's goal, not feature-completeness.

## P2.1 No website or keyword blocking — **worth building**

The reference blocks sites and keywords. FocusBlock blocks apps only, so
Instagram is blocked and `instagram.com` in Chrome is not. For a user whose
problem is scrolling, this is a hole straight through the middle of the product.

- Fix: accessibility service already sees the browser; read the URL bar of known
  browsers and block on domain match. Imperfect but covers the obvious path.

## P2.2 No allowlist mode — **worth building**

The reference can block *everything except* chosen apps. That is the strongest
possible version of the 8:45pm window: nothing but the tools for work.

- Fix: a rule-level flag inverting `covers()` — block everything not in the list,
  with the real allowlist still winning above it.

## P2.3 No launch-count condition — **probably worth it**

The reference can limit *how many times* an app is opened, not just for how
long. Compulsive checking is a count problem more than a duration problem;
`RuleUsage` already has a `launchCount` column that nothing reads.

- Fix: add a launch-count condition to `BlockRule` and evaluate it in the engine.
  Most of the plumbing exists.

## P2.4 No partner/approval unlock — **skip**

The reference can email a trusted person for approval. Real, but it needs a
backend and a second person. Not worth it for one user.

## P2.5 No location or Wi-Fi conditions — **skip for now**

The user's problem is time-shaped, not place-shaped. Revisit if "only at home"
turns out to matter.

## P2.6 No block-screen customisation — **skip**

Timeout, counters and custom designs are reference features that do not move
this user's goal.

---

# P3 — correctness and hygiene

## P3.1 `rule_usage` grows without bound

Hourly buckets accumulate forever. `pruneDailyUsageBefore` and
`pruneHourlyUsageBefore` exist in the DAO but **nothing calls them**.

- Fix: call both from the daily boundary alarm. Keep ~90 days.

## P3.2 Old tables are retained and may still be written

The migration deliberately left every legacy table in place. Some legacy write
paths may survive, creating divergent state nothing reads.

- Fix: audit for writers to the old tables; drop the tables once the new model is
  trusted on device.

## P3.3 Overnight windows are measured only to midnight

The protected-windows figure in Insights splits an overnight routine at
midnight, so a 22:00–06:00 window reports two partial nights.

- Fix: stitch the two halves when computing the stat.

## P3.4 Protected-window minutes are unattributed

The figure counts minutes of a routine's apps used inside its window without
distinguishing an allowlisted app from a block that failed. Honest but blunt.

- Fix: exclude allowlisted packages, and note rules created mid-window.

## P3.5 Timezone and DST are untested

A 22:00–06:00 rule across a DST change has never been reasoned about or tested.

- Fix: unit tests around the transition; decide explicitly whether the window
  shortens, lengthens, or holds wall-clock.

## P3.6 The widget was never reviewed against the rule model

It predates the engine rewrite and may render stale or meaningless state.

- Fix: review, or remove it until it can be done properly.

## P3.7 No backup or export of configuration

Lose the phone, lose every routine and the allowlist.

- Fix: export/import rules and allowlist as JSON.

## P3.8 No end-to-end test of the accessibility service

Everything under it is tested; the component everything depends on is not.

- Fix: an instrumented test that launches a target app and asserts the block
  screen appears.

---

# Deliberately not fixing

- **Uninstall protection.** Device-admin escalation for a self-imposed blocker is
  not worth the trust cost or the recovery risk.
- **Multi-device sync.** One phone, one user, no backend.
- **Any scoring, streak, grade or motivational copy.** Removed on purpose and
  must not return.
- **Goal fields, intention prompts, reflection steps.** The blocker must stay
  usable without the keyboard.

---

# Acceptance

Done when, on the real phone:

1. 8:45pm Monday arrives and the apps are blocked, without the user doing
   anything that evening.
2. Opening a blocked app shows a screen naming the routine and its end time.
3. It still works the next morning, and the morning after.
4. Granting every permission is one flow ending in a confirmation.
5. Opening the app at any hour makes its current state obvious in one glance.
6. If enforcement ever stops, the app says so before the user discovers it.
7. Nothing on screen claims something the engine does not do.
