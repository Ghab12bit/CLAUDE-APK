# FocusBlock — design prompt

A brief for a full visual and interaction pass over every screen. Written to be
executable: every screen has a stated job, a layout, and a rule for what it must
never do.

---

## 0. Required input I do not have

**AppBlock screenshots.** The reference app's site is unreachable from the build
environment, so the current design was never modelled on it — only its data
architecture was. Before implementing, supply:

- AppBlock home / profile list
- AppBlock's block screen (the one you see when you open a blocked app)
- AppBlock's strict-mode and schedule-creation screens

Without these, this brief is grounded in the Codex prototype's visual language
(which the user explicitly approved) and in the failures observed in the current
build — not in AppBlock itself. **Do not claim the result follows AppBlock until
those screenshots exist.**

---

## 1. Who this is for

One user, real constraints, no personas:

- Gets home from the gym around 8pm. Wants 8:45–10:30pm for client outreach and
  building things.
- Scrolls Facebook, Reddit, YouTube, Instagram for hours. Screen time has run
  ~6h/day.
- **Has built a blocker before and did not use it**, because he couldn't tell
  what any of its settings were doing.
- Needs WhatsApp and WhatsApp Business reachable at all times — clients.
- Wants to be *held* to the plan, because in-the-moment desire beats intention.

The failure mode to design against is not "too few features." It is **"I open
the app and cannot tell whether it is doing anything."**

---

## 2. What is wrong with the current build

Observed from real device screenshots, not theory:

1. **Nothing signals state.** A fully protected phone and a completely broken one
   look nearly identical — a column of same-coloured cards.
2. **Explanatory text outweighs state.** Each permission row spends three lines
   justifying itself, at the same size and weight as the thing it describes.
3. **Empty screens stay empty.** Outside a routine's window the home screen has
   almost nothing on it, and says nothing about what that means.
4. **Five permissions = five separate trips** to five different system screens,
   with no sense of progress and no way to know how many are left.
5. **Insights is a different app.** Blue circular icons, different card shapes,
   different spacing from every other screen.
6. **No moment of confirmation.** Finishing setup, or a routine starting, passes
   with no acknowledgement at all.

---

## 3. Principles

1. **State before explanation.** Every screen answers "is this working?" in the
   first 200px, at a size readable at arm's length. Explanation is available,
   demoted, and often collapsed.
2. **One signal colour.** Lime `#C8F169` on near-black `#07090D` means
   *protection is live*. It is never decorative. If lime is on screen,
   something is genuinely being enforced. A second accent (red) means *broken*.
   Nothing else competes.
3. **Every screen has a subject.** No screen is a list of settings. Home's
   subject is "what is blocked now." Routines' is "what runs without me."
   Insights' is "what actually happened."
4. **Honest emptiness.** An empty state explains *why* it is empty and what will
   change it — never a blank card.
5. **No invented metrics.** No attention score, no streak, no grade. Measured
   minutes only. The app does not judge.
6. **Nothing requires typing.** The blocker must be fully usable without the
   keyboard. Optional notes are optional.

---

## 4. Design tokens

```
Background       #07090D   near-black, the page
Surface          #11161E   quiet containers
Card             #141A23   raised content
Signal           #C8F169   protection is live — the only loud colour
SignalDim        #8FAE4A   armed but not currently firing
SignalGlow       #1FC8F169 tinted fill behind live state
Danger           #F85149   not working / destructive
Warn             #F0883E   degraded, needs attention
TextPrimary      #FFFFFF
TextSecondary    #9BA6B2
TextTertiary     #5C6570
```

**Type scale** — the current build's biggest failing is that everything is
14sp. Enforce hierarchy:

```
Hero state       30–34sp  Bold      the answer
Screen title     34sp     Bold      identity
Card title       16sp     SemiBold
Body             14sp     Regular
Caption/label    11sp     Bold, 1.4sp tracking, uppercase
```

**Rule:** no screen may have more than ~40 words above the fold. If it does,
collapse something.

---

## 5. Screen by screen

### 5.1 Blocking (home)

**Job:** is it working, what's blocked, what's next, how do I start one.

```
┌────────────────────────────────────────┐
│ ● PROTECTED                            │  ← pulsing lime dot
│                                        │
│ 4 apps blocked                         │  ← 30sp
│ Evening work block · until 10:30 PM    │
├────────────────────────────────────────┤
│ Instagram  Reddit  YouTube  Facebook   │  ← app ICONS, not a text list
│ ↳ Evening work block    until 10:30 PM │
│                              [ Stop ]  │
├────────────────────────────────────────┤
│ ✓ WhatsApp, Maps, Phone always allowed │  ← reassurance, one line
├────────────────────────────────────────┤
│ NEXT UP                                │
│ Sleep            today 10:00 PM        │
├────────────────────────────────────────┤
│ [        Block now  ·  7 apps       ]  │  ← lime, full width
└────────────────────────────────────────┘
```

Requirements:
- Blocked apps shown as **launcher icons in a grid**, not a comma-separated
  string. The user recognises icons instantly and reads names slowly.
- Grouped **by rule**, so overlap is visible; ending one rule must visibly
  leave the other standing.
- **Idle state** is a real design, not an absence: `STANDING BY / Nothing
  blocked / Evening work block starts in 6h 12m` with a countdown.
- **Broken state** takes over the whole hero in red with a single primary
  action, not a link.

### 5.2 Routines

**Job:** what runs without me, and let me change it.

- Hero: `2 routines · 1 running now`.
- Each routine card: name, time range as a **horizontal 24h bar** with the
  active window filled lime, day pills, app icon row, lock glyph if committed.
- The card should be readable in under a second without parsing text.
- Active routine gets a lime left edge and tinted fill.
- FAB opens templates, not a blank form.

### 5.3 Routine editor

Currently a cramped bottom sheet with `-`/`+` steppers. Replace:

- **Full screen**, not a sheet.
- Time: a **dual-handle arc or slider over a 24-hour track**, showing the
  window as a filled arc. Steppers are a fallback for fine adjustment.
- Days: seven pills, weekday/weekend/every-day shortcuts above them.
- Apps: icon grid with search, selected state = lime ring around the icon.
- Commitment: three cards, terms visible on each, chosen last.

### 5.4 Permissions — one guided flow

**This is the priority.** Currently five rows, five taps, five system screens,
no sense of progress.

Replace with a **single sequential flow**, one permission per screen:

```
┌────────────────────────────────────────┐
│  ●●○○○                                 │  ← progress, lime for done
│                                        │
│  Let FocusBlock see which              │  ← 30sp, plain language
│  app is open                           │
│                                        │
│  Without this, nothing can be blocked. │
│  This is the one that does the work.   │
│                                        │
│  [ screenshot/diagram of the toggle    │
│    you're about to look for ]          │  ← show the system screen
│                                        │
│  [         Open settings           ]   │  ← lime
│  Skip for now                          │  ← quiet
└────────────────────────────────────────┘
```

Requirements:
- **Auto-advance on return.** When the user comes back with the permission
  granted, move to the next step automatically — do not make them find it.
- **Show what they're looking for.** A diagram of the system toggle. Every
  OEM buries these differently; a hint like "Settings → Accessibility →
  Downloaded apps" prevents the most common failure.
- **Order by importance:** accessibility → usage access → battery → overlay →
  exact alarms. Blocking works after the first two; say so, and let them stop.
- **End with confirmation**, not silence: `You're protected` with the lime
  hero, then straight into the app.
- Re-entrant from Setup at any time, resuming where it left off.

### 5.5 Block screen

The single most important screen — it is the moment the app justifies itself.

```
        [ app icon, large, desaturated ]

              Instagram
              is blocked

        by Evening work block
        until 10:30 PM

        Also blocked by Daily budget

        ──────────────────────────
        [ Close ]     I need to unlock
```

- Calm, near-black, no alarm styling. This is not a punishment screen.
- **Name the routine and the end time**, always.
- Overlap stated up front, so ending one rule never looks like it will free
  the app.
- Unlock path visible but quiet, with its cost stated before it is taken.
- Never guilt, never a motivational quote, never a streak.

### 5.6 Insights

- Same title treatment, same section headings, same lime as everywhere else.
- Lead with a **single sentence in plain language**: `2h 14m today, 1h less
  than yesterday.`
- Keep: daily/weekly totals, per-app time, hourly timeline, peak hour.
- Keep and elevate: **protected windows** — minutes of blocked apps used
  inside each routine's own window, and block-screen appearances.
- Remove: anything that reads as a score, and the "AI" sparkle.
- Bars and charts use lime for "went the way you wanted", grey otherwise.

### 5.7 Setup (first run)

Five steps, already structured. Needs the visual pass:
- Step dots lime for complete.
- Each step's headline at 30sp.
- App picking uses the same icon grid as everywhere else.
- Final screen is a **real confirmation moment** — lime hero, the routine
  summarised, one button into the app.

---

## 6. Components to build

| Component | Used by | Note |
|---|---|---|
| `StatusHero` | every screen | LIVE / IDLE / BROKEN all visually distinct |
| `AppIconGrid` | home, editor, setup | launcher icons, lime ring when selected |
| `DayPills` | editor, setup | + weekday/weekend shortcuts |
| `TimeWindowBar` | routine card, editor | 24h track, window filled |
| `PermissionStep` | guided flow | one permission, full screen, auto-advance |
| `SectionHeading` | all | uppercase, tracked, tertiary |
| `EmptyState` | all | always explains why + what changes it |

---

## 7. Non-goals

Explicitly out of scope, and must not reappear:

- Goal fields, intention prompts, reflection steps, journaling.
- Attention scores, focus scores, streaks, grades, leaderboards.
- Motivational copy, quotes, encouragement, emoji rewards.
- Claims about repairing attention, resetting dopamine, or rewiring anything.
- Notifications that are not either (a) the persistent protection notification
  or (b) a routine about to start.
- Any control that does not change behaviour. A switch that does nothing is
  worse than no switch.

---

## 8. Acceptance

The design is done when, on a real device:

1. A glance at any screen answers "is blocking on?" without reading.
2. Granting all permissions takes one flow and ends with a confirmation.
3. The home screen is informative when nothing is blocked.
4. Opening a blocked app names the routine and the time it lifts.
5. Every screen looks like the same app.
6. Nothing on screen claims something the engine does not do.
