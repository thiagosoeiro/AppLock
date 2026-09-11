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
| 8 | [Intruder photo or video](#8-intruder-photo-or-video) | Medium to large | Not started |
| 9 | [Unlock report and log viewer](#9-unlock-report-and-log-viewer) | Small to medium | Not started |
| 10 | [Timeout per app](#10-timeout-per-app) | Medium | Not started |
| 11 | [Lock new apps automatically](#11-lock-new-apps-automatically) | Small to medium | Not started |
| 12 | [Re-lock everything from Quick Settings](#12-re-lock-everything-from-quick-settings) | Small | Not started |
| 13 | [Send the log off the phone](#13-send-the-log-off-the-phone) | Medium | Not started |
| 14 | [Lock remotely by message](#14-lock-remotely-by-message) | Medium | Not started |
| 15 | [Spoken warning or alarm](#15-spoken-warning-or-alarm) | Small | Not started |
| 16 | [Screen timeout by network](#16-screen-timeout-by-network) | Small to medium | Not started |
| 17 | [Lock-screen notification content by network](#17-lock-screen-notification-content-by-network) | Small to medium | Not started |

## Against someone holding the unlocked phone

The disguise, anti-uninstall and the wrong-try limit are aimed at someone who takes the phone while
it is unlocked. These ideas go further in that direction.

### 1. Wrong tries lock the whole phone

After a chosen number of wrong PINs, patterns or passwords on any locked app, lock the phone and
re-lock every app. Someone guessing at one app lands on the phone's own lock screen instead of
moving on to the next app.

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

### 13. Send the log off the phone

After wrong tries, send the log entry, and the photo or video from item 8, somewhere off the phone.
Anything kept only on the phone leaves with the thief, so this is what makes item 8 useful against
theft.

- **Check first:** the app has no internet permission today. Sending by email or a messaging bot
  would add one, which ends the README's promise that all data stays on the device. An SMS to a
  trusted number needs the SMS permission but no internet; it carries text only, and stops once the
  SIM is removed. Handing the event to an automation app that does the sending keeps this app
  offline, but the event must go to that one app, not to every app on the phone (see F6 in
  [SECURITY_AUDIT.md](SECURITY_AUDIT.md)).

### 14. Lock remotely by message

Send a secret keyword from another phone, by SMS or a messaging app, to switch protection on,
re-lock every app and optionally lock the phone. Useful when the phone is lost, or lent to someone.

- **Builds on:** the lock action from item 1, and the protection switch behind the automation
  intents in [AUTOMATION.md](AUTOMATION.md). An automation app that can react to incoming messages
  can already send `ENABLE_PROTECTION` today; this would build it in.
- **Check first:** reading SMS needs the SMS permission, and reading other messaging apps needs
  notification access, shared with item 7. The keyword shows in the notification and stays in the
  chat, where anyone holding the phone can read it, so pick one that looks like an ordinary message.
  That is harmless for locking but rules out a fixed unlock keyword, which anyone who saw it could
  reuse; a remote unlock would need a code that works only once. A phone in airplane mode receives
  nothing, which item 2 covers.

## Privacy from people nearby

### 7. Hide notification content for locked apps

[Upstream #230](https://github.com/aload0/AppLock/issues/230). A locked messaging app still shows
the sender and message text in the notification shade, including SMS codes someone could use to
take over accounts.

- **Check first:** it needs notification access. Android can't hide another app's notification and
  restore it later, so the realistic version removes the original and posts a generic "New
  notification" that opens the app through the lock. Quick reply is lost, and the generic
  notification has to follow the disguise.

### 8. Intruder photo or video

[Upstream #229](https://github.com/aload0/AppLock/issues/229). After a chosen number of wrong
tries, for example 3, take a front-camera photo or record a short video, and show it with the
security log, behind the PIN. It helps against people nearby; against a thief it only helps if
item 13 sends it off the phone.

- **Check first:** it needs the camera permission, and the microphone for video with sound. Android
  limits camera use by apps in the background, so check that the lock screen is allowed to use it.
  The green camera indicator shows while it records, and some phones play a sound when a video
  starts, so the person may notice.

### 9. Unlock report and log viewer

After a correct unlock, show what happened since the last one, for example "3 wrong tries on [app]
at 14:02". Optionally record successful unlocks too: which app, when, and whether by PIN or
fingerprint. A log viewer behind the PIN would save exporting `audit_log.txt` to read it.

- **Builds on:** `UnlockAttemptLimiter`, which stores only a count today. `audit_log.txt` is a debug
  log, written only while **Settings → Advanced → Logging** is on, so neither keeps a record of each
  try.
- **Effort:** small for the report, medium for the viewer.

### 15. Spoken warning or alarm

After the chosen number of wrong tries, speak a message you write, such as "Don't touch my phone",
or sound an alarm, to scare off someone nearby who is guessing.

- **Builds on:** Android's text-to-speech, which needs no permission, and the same wrong-try count
  as items 1 and 8.
- **Check first:** it gives the disguise away and works against items 5 and 6, which rely on showing
  nothing. A thief who hears it may just switch the phone off, and during a robbery it could
  provoke. Playing at alarm volume keeps silent mode from muting it.

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

## Following trusted Wi-Fi

Two phone settings that could switch with the trusted networks from
[AUTOMATION.md](AUTOMATION.md#built-in-trusted-wi-fi): relaxed on a trusted network, strict
everywhere else. Both share this design:

- **Own switches, shared networks.** Each setting gets its own switch and uses the same trusted
  network list, so neither requires letting locked apps open on trusted Wi-Fi.
  `TrustedNetworkMonitor` only runs while "Open locked apps on trusted Wi-Fi" is on today, so it
  would have to run while any of the three is on.
- **Fail closed, although Android stores the value.** Trust lives in memory, but these settings are
  saved by Android and outlive the app. Write the strict value whenever the app starts, whenever
  trust drops and when the switch is turned off, and the relaxed value only while trust holds.
  `TrustedNetworkMonitor.updateState` acts only on a change and trust starts out false, so the write
  at start has to be added.
- **No timer.** Writes follow the trust changes the app already tracks. If Android misses the phone
  leaving a network, the screen-on check drops trust, so a wrong value lasts only a moment after
  the screen turns on.

### 16. Screen timeout by network

A longer screen timeout on a trusted network and a shorter one everywhere else, which shrinks the
time a snatched phone stays unlocked away from home. Both durations are settings.

- **Builds on:** `Settings.System.SCREEN_OFF_TIMEOUT`. It needs "Modify system settings", granted
  from a switch in the phone's Settings.
- **Check first:** a timeout changed by hand is overwritten at the next trust change.

### 17. Lock-screen notification content by network

Show notification content on the lock screen on a trusted network and hide it everywhere else.
Unlike item 7, this covers the lock screen only, for every app.

- **Builds on:** the secure setting `lock_screen_allow_private_notifications` (1 shows content, 0
  hides it). It needs `WRITE_SECURE_SETTINGS`, which only ADB or Shizuku can grant:
  `adb shell pm grant dev.pranav.applock android.permission.WRITE_SECURE_SETTINGS`, or through
  Shizuku, which the app already uses. The grant survives app updates but not an uninstall.
- **Check first:** that Android accepts this write from an app targeting SDK 37. Without the
  permission the switch should stay off.

## To check before new features

Open upstream reports of ways around the lock, two of them on Samsung. None has been checked
against this fork yet:

- [#228](https://github.com/aload0/AppLock/issues/228): cancelling the biometric prompt with "Use
  PIN" can leave the lock screen state stuck.
- [#234](https://github.com/aload0/AppLock/issues/234): on One UI, the lock overlay crashes and
  fails to re-lock after the system biometric prompt.
- [#240](https://github.com/aload0/AppLock/issues/240): a One UI popup gets around the lock.
