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
| 8 | [Intruder photo or video](#8-intruder-photo-or-video) | Medium to large | Built, see [INTRUDER_ALERTS.md](INTRUDER_ALERTS.md) |
| 9 | [Unlock report and log viewer](#9-unlock-report-and-log-viewer) | Small to medium | Not started |
| 10 | [Timeout per app](#10-timeout-per-app) | Medium | Not started |
| 11 | [Lock new apps automatically](#11-lock-new-apps-automatically) | Small to medium | Not started |
| 12 | [Re-lock everything from Quick Settings](#12-re-lock-everything-from-quick-settings) | Small | Not started |
| 13 | [Send the log off the phone](#13-send-the-log-off-the-phone) | Medium | Built (email), see [INTRUDER_ALERTS.md](INTRUDER_ALERTS.md) |
| 14 | [Lock remotely by message](#14-lock-remotely-by-message) | Medium | Built, see [REMOTE_LOCK.md](REMOTE_LOCK.md) |
| 15 | [Spoken warning or alarm](#15-spoken-warning-or-alarm) | Small | Not started |
| 16 | [Screen timeout by network](#16-screen-timeout-by-network) | Small to medium | Built, see [AUTOMATION.md](AUTOMATION.md#screen-timeout-by-network) |
| 17 | [Lock-screen notification content by network](#17-lock-screen-notification-content-by-network) | Small to medium | Built, see [AUTOMATION.md](AUTOMATION.md#lock-screen-notification-content-by-network) |
| 18 | [Secure Folder copies of locked apps](#18-secure-folder-copies-of-locked-apps) | Small (list fix) to large (per copy) | List fix and cleanup in [PR #25](https://github.com/thiagosoeiro/AppLock/pull/25), chunk 7 in [SECURITY_FIXES.md](SECURITY_FIXES.md) |

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

**Built as email, together with item 8 — see [INTRUDER_ALERTS.md](INTRUDER_ALERTS.md).** The chosen
route was email through Resend, which adds the internet permission; the SMS and automation-app routes
below were not taken. The README's "all data stays on the device" line now names this exception.

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

**Built — see [REMOTE_LOCK.md](REMOTE_LOCK.md).** Both routes were built: SMS, and notification
access, the only one that sees RCS and chat apps. A match switches protection on, re-locks every
app, locks the phone if that switch is on, and emails a short report through the intruder alert
email. Trusted Wi-Fi still applies afterwards, and there is no remote unlock.

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

**Built — see [INTRUDER_ALERTS.md](INTRUDER_ALERTS.md).** It captures a front-camera photo or a short
video after a run of wrong tries and emails it with item 13, rather than keeping it on the phone: a
capture left on the phone leaves with a thief. There is no on-device gallery.

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

Two phone settings that switch with the trusted networks from
[AUTOMATION.md](AUTOMATION.md#built-in-trusted-wi-fi): relaxed on a trusted network, strict
everywhere else. Both share this design:

- **Own switches, shared networks.** Each setting gets its own switch and uses the same trusted
  network list, so neither requires letting locked apps open on trusted Wi-Fi.
  `TrustedNetworkMonitor` runs while any option that follows trust is on
  (`AppLockRepository.usesTrustedNetworks`, which counts both items), and only "Open locked apps on
  trusted Wi-Fi" re-locks when trust ends.
- **Fail closed, although Android stores the value.** Trust lives in memory, but these settings are
  saved by Android and outlive the app. Write the strict value whenever the app starts, whenever
  trust drops and when the switch is turned off, and the relaxed value only while trust holds.
  Both items write from `TrustedNetworkMonitor.refresh`, which runs at every start while trust is
  still false, and from `updateState` on each trust change.
- **No timer.** Writes follow the trust changes the app already tracks. If Android misses the phone
  leaving a network, the screen-on check drops trust, so a wrong value lasts only a moment after
  the screen turns on.

### 16. Screen timeout by network

**Built — see [AUTOMATION.md](AUTOMATION.md#screen-timeout-by-network).** It has its own switch under
Settings → Trusted Wi-Fi, with 30 seconds on trusted Wi-Fi and 15 seconds elsewhere by default, and
follows the shared design above. On its own it never lets locked apps open or shows HOME.

A longer screen timeout on a trusted network and a shorter one everywhere else, which shrinks the
time a snatched phone stays unlocked away from home. Both durations are settings.

- **Builds on:** `Settings.System.SCREEN_OFF_TIMEOUT`. It needs "Modify system settings", granted
  from a switch in the phone's Settings.
- **Check first:** a timeout changed by hand is overwritten at the next trust change.

### 17. Lock-screen notification content by network

**Built — see [AUTOMATION.md](AUTOMATION.md#lock-screen-notification-content-by-network).** It has
its own switch under Settings → Trusted Wi-Fi and follows the shared design above. The permission is
granted once, through Shizuku or the adb command below. On its own it never lets locked apps open or
shows HOME.

Show notification content on the lock screen on a trusted network and hide it everywhere else.
Unlike item 7, this covers the lock screen only, for every app.

- **Builds on:** the secure setting `lock_screen_allow_private_notifications` (1 shows content, 0
  hides it). It needs `WRITE_SECURE_SETTINGS`, which only ADB or Shizuku can grant:
  `adb shell pm grant dev.pranav.applock android.permission.WRITE_SECURE_SETTINGS`, or through
  Shizuku, which the app already uses. The grant survives app updates but not an uninstall.
- **Check first:** that Android accepts this write from an app targeting SDK 37. Without the
  permission the switch should stay off.

## Secure Folder

### 18. Secure Folder copies of locked apps

**Fixed in PR #25**, chunk 7 of `SECURITY_FIXES.md`, and in phone testing. Found in use on 2026-09-16.
What shipped is option A without its second lookup step, plus cleanup from the uninstall broadcast.
The copies are still not told apart. The findings below stay for options B, C, E and G, if wanted
later.

**The bug.** A protected app is installed both outside and inside Secure Folder:

1. It is uninstalled outside Secure Folder.
2. Its row leaves the main screen.
3. The copy inside Secure Folder still gets the lock screen, and there is no row left to unprotect
   it.

**Cause.** This is in the original code, not a regression from PR #22.

- **The lock list holds package names only** (`LockedAppsRepository`, `locked_apps`). The services
  check the name alone (`AppLockAccessibilityService.checkAndLockApp`, and the same in the Shizuku
  and Usage Stats services). Secure Folder's copy has the same name, so both copies lock.
- **The main screen built rows only for apps installed outside Secure Folder.**
  `AppSearchManager.loadApps(packageNames)` called `getApplicationInfo(name, 0)`, which sees only
  the main user's apps, and dropped any name it couldn't find. Before PR #22 the list filtered
  `getInstalledApplications(0)` and dropped the same names.
- **Nothing ever removed an entry except the unlock icon** (`MainViewModel.unlockApp`). No receiver
  listened for uninstalls.

**What can see Secure Folder?** Checked against the AOSP source:

- **Accessibility service (the backend in use): no.**
  - `AccessibilityEvent`, `AccessibilityRecord` and `AccessibilityWindowInfo` carry no user, uid
    or profile.
  - A window has a task id, but `AccessibilityWindowInfo.getTaskId()` is `@hide`. Turning a task
    into a user needs `REAL_GET_TASKS`, which only system apps get.
- **Package lookups: not on Android 16.**
  - `getApplicationInfo(name, MATCH_UNINSTALLED_PACKAGES)` used to widen to every user for a caller
    with a profile. Android 16 removed that (flag `remove_cross_user_permission_hack`, on in release
    config `bp1a`).
  - One UI 8 reportedly makes Secure Folder a "private" profile. `LauncherApps` and
    `UserManager.getUserProfiles` hide those from apps that aren't the launcher.
- **Usage Stats events: only by guessing.**
  - `UsageStatsManager.queryEvents` returns only the calling user's events. Another user's events
    need `INTERACT_ACROSS_USERS_FULL`, which adb can't grant.
  - A Secure Folder app never appears there, so a copy could only be told by a missing event. A late
    or missing event for the copy outside Secure Folder would then let it open unlocked. That fails
    open.
- **Process state with Usage access: likely yes, unconfirmed on One UI.**
  - `ActivityManagerService.getPackageProcessState(pkg)` doesn't filter by user, so it counts
    Secure Folder's copy. `getUidProcessState(uid)` answers for the caller's own user only. Both need
    only the Usage access app op.
  - The app is on top while the copy outside isn't: the one on screen is inside Secure Folder.
  - Hidden API, called through the HiddenApiBypass library the app already has. Switching between
    the two copies can race. A UID observer on the outside copy covers that, and it is allowed for
    the caller's own user.
- **Uninstall broadcast: yes, for cleanup.** `ACTION_PACKAGE_FULLY_REMOVED` carries the hidden extra
  `android.intent.extra.REMOVED_FOR_ALL_USERS`, false while a copy remains in another user
  (`BroadcastHelper.sendPackageRemovedBroadcasts`).
- **`INTERACT_ACROSS_USERS` granted by adb: package info only.** A development permission, like
  `WRITE_SECURE_SETTINGS` in item 17, and it survives reboots. Other users' `getApplicationInfo`
  and `getInstalledPackages` need only this, so names, icons and the "+" sheet could include Secure
  Folder. It doesn't tell copies apart at lock time.
- **Shizuku: yes, exactly.**
  - `IActivityTaskManager.getTasks`, already used by `ShizukuActivityManager.getTasksWrapper`,
    returns other users' tasks when the caller holds `INTERACT_ACROSS_USERS`, which shell does
    (AOSP `RunningTasks`).
  - Each task carries `TaskInfo.userId`, already in the hidden-api stub. On Samsung, Secure Folder
    is usually user 150 and Dual Messenger user 95.
  - The Shizuku backend reads these tasks but ignores `userId` today, so it locks both copies too.
  - Samsung blocks shell commands such as `pm list packages` for user 150. AOSP's binder calls don't
    check that restriction, but One UI may.
  - Without root, Shizuku usually has to be started again after a reboot.

**Shipped (PR #25).**

- Every protected package gets a row. One not installed outside Secure Folder shows its saved name,
  or its package name, with the generic app icon and "Not installed outside Secure Folder".
- Names are saved in their own prefs file while apps are installed.
- `PackageRemovedReceiver` removes an entry only when the uninstall broadcast says the app is gone
  for every user.
- Every copy of a protected package still locks.
- Limits:
  - An app installed only inside Secure Folder can't be added from "+". Once unprotected, it stays
    unprotected until it is installed outside again.
  - A Secure Folder copy uninstalled later sends the main user nothing, so its entry stays listed.

**Not taken, for later.**

- **B. Protect each copy separately, with Shizuku (large).** Separate rows for the copy outside and
  inside Secure Folder, and a "+" sheet with Secure Folder apps. It changes the lock path, the list
  and the saved data.
- **C. One switch (medium).** "Lock apps inside Secure Folder", on or off, for every protected app.
  With Shizuku, or with E.
- **D. Leave Secure Folder to its own lock (small).** Skip names not installed outside Secure Folder.
  An app installed in both places would still lock in both.
- **E. Process state with Usage access (medium).** Tells the copies apart without Shizuku or adb, as
  above, for C or a per-app setting.
- **G. `INTERACT_ACROSS_USERS` by adb or once through Shizuku (small).** Real names and icons for
  Secure Folder rows, and Secure Folder apps in the "+" sheet.

**Check first.**

- **For B or C with Shizuku:** that `getTasks` reports user 150 for an app opened inside Secure
  Folder.
- **For E:** log both process states while opening each copy.
- **For G:** that the grant works, and that a lookup in user 150 isn't blocked.

## To check before new features

Open upstream reports of ways around the lock, two of them on Samsung. None has been checked
against this fork yet:

- [#228](https://github.com/aload0/AppLock/issues/228): cancelling the biometric prompt with "Use
  PIN" can leave the lock screen state stuck.
- [#234](https://github.com/aload0/AppLock/issues/234): on One UI, the lock overlay crashes and
  fails to re-lock after the system biometric prompt.
- [#240](https://github.com/aload0/AppLock/issues/240): a One UI popup gets around the lock.
