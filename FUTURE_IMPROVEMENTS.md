# Future improvements

Ideas for this fork that are not built yet, collected on 2026-09-11. None of them is planned; each
entry says what it would do, why, and what to check before building it. Effort is a rough guess.
When one ships, set its Status to the PR that built it.

Anything a stranger could see — a Quick Settings tile, a notification, a dialog — has to keep the
"System Services" disguise described in [ANTI_UNINSTALL.md](ANTI_UNINSTALL.md).

| # | Idea | Effort | Status |
| --- | --- | --- | --- |
| 1 | [Wrong tries lock the whole phone](#1-wrong-tries-lock-the-whole-phone) | Small | Not started |
| 2 | [Tripwires: airplane mode or SIM removal](#2-tripwires-airplane-mode-or-sim-removal) | Small to medium | Not started |
| 3 | [Shuffled PIN pad](#3-shuffled-pin-pad) | Small | Not started |
| 4 | [A believable front for "System Services"](#4-a-believable-front-for-system-services) | Medium | Not started |
| 5 | [Fake crash on locked apps](#5-fake-crash-on-locked-apps) | Medium | Not started |
| 6 | [Duress PIN](#6-duress-pin) | Medium to large | Not started |
| 7 | [Hide notification content for locked apps](#7-hide-notification-content-for-locked-apps) | Medium to large | Not started |
| 8 | [Intruder photo](#8-intruder-photo) | Medium to large | Not started |
| 9 | [Wrong-try report and log viewer](#9-wrong-try-report-and-log-viewer) | Small to medium | Not started |
| 10 | [Timeout per app](#10-timeout-per-app) | Medium | Not started |
| 11 | [Lock new apps automatically](#11-lock-new-apps-automatically) | Small to medium | Not started |
| 12 | [Re-lock everything from Quick Settings](#12-re-lock-everything-from-quick-settings) | Small | Not started |

## Against someone holding the unlocked phone

The disguise, anti-uninstall and the wrong-try limit are aimed at someone who takes the phone while
it is unlocked. These ideas go further in that direction.

### 1. Wrong tries lock the whole phone

After a number of wrong PINs, patterns or passwords on any locked app, lock the phone and re-lock
every app. Someone guessing at one app lands on the phone's own lock screen instead of moving on to
the next app.

- **Builds on:** `UnlockAttemptLimiter` already counts wrong tries, `AppLockAccessibilityService`
  already locks the phone with `GLOBAL_ACTION_LOCK_SCREEN`, and
  `AppLockManager.clearAllUnlockStates` re-locks every app.
- **Check first:** locking the phone that way needs the accessibility service switched on, which
  only the Accessibility backend and anti-uninstall require. Device admin could lock the phone too,
  but it declares no policies today, so that would mean adding `force-lock` and probably granting
  device admin again.

### 2. Tripwires: airplane mode or SIM removal

While the phone is unlocked, airplane mode switching on or the SIM being removed locks the phone and
re-locks every app. Both are a thief's first moves to stop the phone being tracked.

- **Builds on:** the lock action from item 1. A running service can listen for airplane mode
  changes.
- **Check first:** SIM removal is harder to detect reliably; test it on One UI. One UI's own Theft
  protection (Offline device lock) may already cover part of this, so compare first; this version
  would act at once. Turning airplane mode on for a flight would lock the phone too.

### 3. Shuffled PIN pad

Put the digits in a new order every time the lock screen shows, so someone watching you type in
public doesn't learn the PIN. It should be optional, since it slows entry. Hiding the pattern trail
([upstream #175](https://github.com/aload0/AppLock/issues/175)) does the same for patterns.

- **Builds on:** the keypad in `PasswordOverlayScreen.kt` and the pattern grid in
  `PatternLockScreen.kt`.

### 4. A believable front for "System Services"

Opening the disguised app from the launcher goes straight to the PIN pad (the start route in
`NavigationManager`), which gives the disguise away. Show a dull, system-style screen instead, with
a secret gesture that opens the PIN.

- **Check first:** hiding the launcher icon outright looks simpler but is riskier. Android may show
  a stand-in icon that opens App info, where the anti-uninstall guard would bounce whoever tapped
  it.

### 5. Fake crash on locked apps

A locked app shows an "App keeps stopping" style message instead of the PIN pad. A thief assumes the
app is broken and doesn't learn that it is protected. A long-press or secret tap opens the real
lock.

- **Builds on:** `LockScreenOverlayManager`, which shows the lock screen over locked apps.
- **Check first:** you have to remember the gesture. The fingerprint prompt reveals that a lock
  exists, so an automatic fingerprint prompt would have to wait for the gesture.

### 6. Duress PIN

A second PIN that seems to work but opens a fake "Couldn't connect" screen, while every locked app
stays locked. It is meant for being forced to unlock an app. It should do nothing visible, such as
locking the phone, that could provoke the person forcing it.

- **Builds on:** `CredentialHasher` for storing the second PIN.
- **Check first:** how it interacts with the wrong-try limit, and that it can never be set to the
  real PIN.

## Privacy from people nearby

### 7. Hide notification content for locked apps

[Upstream #230](https://github.com/aload0/AppLock/issues/230). A locked messaging app still shows the
sender and message text in the notification shade, including SMS codes someone could use to take
over accounts.

- **Check first:** it needs notification access. Android can't hide another app's notification and
  restore it later, so the realistic version removes the original and posts a generic "New
  notification" that opens the app through the lock. Quick reply is lost, and the generic
  notification has to follow the disguise.

### 8. Intruder photo

[Upstream #229](https://github.com/aload0/AppLock/issues/229). Take a front-camera photo after a
number of wrong tries and show it with the security log, behind the PIN. It helps against people
nearby but little against a thief, since the photo stays on the stolen phone.

- **Check first:** it needs the camera permission, and Android limits camera use by apps in the
  background, so check that the lock screen is allowed to use it. The green camera indicator shows
  while the photo is taken.

### 9. Wrong-try report and log viewer

After a correct unlock, show what happened since the last one, for example "3 wrong tries on [app]
at 14:02". A log viewer behind the PIN would save exporting `audit_log.txt` to read it.

- **Builds on:** `UnlockAttemptLimiter`, which stores only a count today; the app and time of each
  try would need recording.
- **Effort:** small for the report, medium for the viewer.

## Convenience

### 10. Timeout per app

Banking apps lock immediately while chat apps stay open for a few minutes.

- **Builds on:** `AppLockManager.shouldShowLockScreen` already tracks the unlock time per app; only
  the duration is a single setting for all apps.

### 11. Lock new apps automatically

When an app is installed, lock it by default, or ask behind the PIN, so a new bank or wallet app
isn't left open.

- **Check first:** a notification asking about it would be visible outside the PIN, so it has to
  follow the disguise, or the question waits until the app is next opened.

### 12. Re-lock everything from Quick Settings

A Quick Settings tile that re-locks every app at once, for handing the phone to someone.

- **Builds on:** `AppLockManager.clearAllUnlockStates`.
- **Check first:** the tile's label and icon show outside the PIN and have to follow the disguise.

## To check before new features

Open upstream reports of ways around the lock, two of them on Samsung. None has been checked against
this fork yet:

- [#228](https://github.com/aload0/AppLock/issues/228): cancelling the biometric prompt with "Use
  PIN" can leave the lock screen state stuck.
- [#234](https://github.com/aload0/AppLock/issues/234): on One UI, the lock overlay crashes and fails
  to re-lock after the system biometric prompt.
- [#240](https://github.com/aload0/AppLock/issues/240): a One UI popup gets around the lock.
