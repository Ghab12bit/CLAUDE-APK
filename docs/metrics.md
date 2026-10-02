# FocusBlock — Activity metrics

Every number on the Activity tab, in notifications and in suggestions comes from data stored on the
phone. Each one is listed here with its formula and source (spec 4.8). There is no focus score.
"Time saved" is not shown, because no defensible formula exists (open decision 12.3 #6).

## Sources

| Source | What it holds | Written by |
|---|---|---|
| Android `UsageStatsManager.queryEvents` | Activity resume, pause and stop events, screen off, keyguard, shutdown | Android. FocusBlock reads it only with Usage access. |
| `block_sessions` | Every immediate block: start, planned end, type, strength, intention, end reason, outcome | `SessionManager` |
| `block_logs` | Every blocked opening: app, time, reason, rule, attempt number, action taken, strength | `Enforcer` (both enforcement services) |
| `unlock_events` | Every Open anyway and emergency request or grant: app, time, reason text, length, strength | `OverrideManager` |
| `recommendations` | Every suggestion shown, applied or dismissed | `RecommendationRepository` |

## Foreground time (`UsageCalculator`)

- An app is in the foreground from its first `ACTIVITY_RESUMED` until its `ACTIVITY_PAUSED` or
  `ACTIVITY_STOPPED`.
- When another app resumes, any app still open is closed at that moment. Time is never counted twice;
  in split screen it goes to the most recently resumed app.
- `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN` and `DEVICE_SHUTDOWN` close every open app.
- Each query starts one day (or two hours, for today) before the window, so an app already open at
  the window start is counted. Intervals are then clipped to the window.
- An interval that crosses midnight is split between the two days (local time zone).
- If Usage access is missing, or Android returns no data, the value is **unavailable**, never zero.

## Activity tab

The tab has three views. **Day** shows one day; the arrows step back up to 27 days. **Week** shows
7 days ending today; the arrows step back up to 3 weeks. **Trend** shows the last 4 weeks. Every
number below covers the range on screen. The arithmetic is in `policy/Insights.kt` and
`policy/UsageCalculator.kt`, and is unit-tested in `InsightsTest`.

### App categories

Every app counts as **Distracting**, **Neutral** or **Productive**. The category you choose (tap an
app under Most used apps) always wins. Otherwise FocusBlock uses a short list of well-known apps,
then the category the developer declared in the Play Store listing (`ApplicationInfo.category`):
games, video, social and news are Distracting; productivity is Productive; everything else is
Neutral. Categories only change how time is shown. They never block anything.

| Metric | Formula | Source |
|---|---|---|
| Screen time (hero number) | Foreground time in the range, across counted apps (all apps except FocusBlock, home-screen launchers, System UI and apps excluded from reports). Trend shows the average per day over days with data instead. | UsageStats |
| "x less / more than your usual" (Day) | The day's screen time compared with the mean of the previous 14 days that have data (at least 3). For today, each day only counts up to the current time of day. Differences under 5 minutes read "about your usual". | UsageStats |
| "x a day · y% less than the week before" (Week) | Screen time ÷ days in the range so far; change = (this range − the 7 days before it) ÷ the 7 days before it | UsageStats |
| Chart (Day) | Foreground time per local hour, stacked by category. On daylight-saving days the 23 or 25 hours are folded into 24 by hour of day. | UsageStats |
| Chart (Week, Trend) | Foreground time per day, stacked by category. The dashed line is the average per day over days with data. | UsageStats |
| Most used apps | Apps with at least 1 minute in the range, by foreground time; the bar is the app's share of the range's screen time. Five are shown, More shows up to 20. Each row also shows blocked attempts; the app sheet shows opens (separate openings, see `launches`) and blocked attempts. | UsageStats, `block_logs` |
| Balance | Screen time ÷ (16 awake hours × days in the range), as a percentage. Awake hours are fixed at 07:00–23:00. | UsageStats |
| Peak time | The local hour of day with the most counted foreground time in the range (ties go to the earlier hour) | UsageStats |
| Usage split | Each category's share of counted foreground time, in whole percent that add up to 100 (largest remainder) | UsageStats |
| Longest focus | The longest time without using the phone while awake (07:00–23:00) between two stretches of use. On today, the time since the last use counts up to now. Time before the first use of the day is not counted. Week and Trend show the longest single day. | UsageStats |
| Continuous use | The longest stretch of phone use. Foreground intervals of every app, including the launcher, are merged when the gap between them is at most 1 minute. | UsageStats |
| Pickups | Unlocks (`KEYGUARD_HIDDEN`) in the range. Phones that never report an unlock (no lock screen) count screen-on events (`SCREEN_INTERACTIVE`) instead. | UsageStats |
| Week by week (Trend) | Each 7-day week's total, its average per day over days with data, and its change from the week before | UsageStats |
| Blocks: Finished / Not yet / Extended / Unanswered / Ended early | Count of `block_sessions` started in the period, grouped by `outcome`. Active blocks, history migrated from older versions (`endReason = LEGACY`) and the first-run test block (`endReason = TEST`) are not counted. | `block_sessions` |
| Blocked attempts by hour | Count of `block_logs` rows in the period, grouped by the local hour of `timestamp` | `block_logs` |
| Emergency and Open anyway unlocks | `unlock_events` requested in the period whose status is not `CANCELLED`, newest first | `unlock_events` |
| Rules working / bypassed | For each rule: attempts = `block_logs` rows in the period with that reason and rule id; bypasses = `GRANTED` `unlock_events` with the same reason type and rule id. A rule is **often bypassed** when it has 3 or more bypasses **and** bypasses ≥ ⅓ of its attempts. | `block_logs`, `unlock_events` |

## Rules tab

| Metric | Formula |
|---|---|
| "Rules cover 6 h 15 min of today" | Size of the union of today's minutes covered by enabled, time-based routines and bedtime, including the after-midnight tail of an overnight window that started yesterday. Limits are not counted, because they do not cover a time. |
| 24-hour strip | The same segments drawn from midnight to midnight, plus a marker for the current time |
| Gap line | See the routine suggestion below; shown only when that suggestion qualifies |
| "x of y used" on a limit row | Foreground time today of the limit's apps (App limit), or of the counted apps (Daily limit), against the allowance |

## Suggestions (`RecommendationEngine`)

Common rules:
- Suggestions use only logged data and are eligible only after **3 days** of data. The first day of
  data is the earliest of the first blocked attempt, the first block and the first usage event in
  the last 14 days.
- A pattern must repeat on **3 or more days**.
- One suggestion is shown at a time, in this order: routine gap, then Strict Lock, then app limit.
- Not now hides a suggestion for 7 days. An applied suggestion is never shown again. Nothing changes
  until Apply is tapped.

| Suggestion | Evidence (shown in one sentence) | Change |
|---|---|---|
| Add a routine | Blocked attempts in a local hour on 3 or more of the last 14 days, where no enabled routine or bedtime covers at least 30 minutes of that hour on those days. The busiest hour (by days, then attempts) is widened to neighbouring qualifying hours, up to 3 hours. | A routine for that window. Its days are weekdays, weekends or every day, depending on which days the evidence fell on. It blocks the up to 5 apps most attempted in the window. |
| Turn on Strict Lock | A Normal routine opened with Open anyway on 3 or more of the last 14 days | That routine becomes Strict Lock |
| Add an app limit | An app with no limit, used 60 minutes or more on 3 or more of the last 7 full days | A limit of 75% of the app's median daily use, rounded down to 5 minutes, with a minimum of 15 minutes |

## Notifications

| Notification | Formula |
|---|---|
| Daily summary (9 PM, off by default) | Today's screen time, as on the Activity tab, plus the count of today's `block_logs` rows |
| Usage reminder (30 and 60 min) | Continuous time in one non-essential app since it came to the foreground, measured by the accessibility service. Sent once per stretch at each threshold. |

## Not shown, on purpose

- **Focus score, productivity score or streaks.** Spec 12.1. Longest focus is a measured duration,
  not a score.
- **Time saved.** There is no defensible counterfactual for how long an app would have been used.
- **Comparisons to other people.** The only baseline is the user's own trailing average.
