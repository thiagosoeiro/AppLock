# Security audit

A static review of the whole repository: all four module manifests, both Gradle build files, the
version catalog, the CI workflow, and every Kotlin and Java source file across `app`, `appintro`,
`patternlock` and `hidden-api`.

| | |
| --- | --- |
| Commit | `8655d0a` |
| Branch | `feature/automation-intents` |
| Version | 2.5.1 (251) |
| Reviewed | 2026-09-09 |
| Follow-up | Anti-uninstall only, at `f5aa5a3` on `master`, 2026-09-10 |
| Fix status | As of 2026-09-11, in the Status column below; details in `SECURITY_FIXES.md` |

Findings are cited by file and line against that commit and will drift as the branch moves. F18–F23
come from the follow-up and are cited against `f5aa5a3`. The findings are left as written at review
time; only the Status column tracks what has changed since.

## Bottom line

No malicious code, no telemetry, no network egress of any kind. The app does what it claims to do,
and nothing it observes can leave the device.

It is not, however, risk free. This is a deliberately high-privilege app — accessibility with
window-content retrieval, device admin, screen overlay, full package visibility — and it stores and
checks its own credentials in ways that fall short of what that privilege level warrants. Its
anti-uninstall protection deters a casual attempt but does not survive safe mode, a force stop or
ADB.

Twenty-three findings; six are worth acting on. Four of the six — F2, F3, F4 and F5 — have since
been fixed, along with F8, F11, F19, F20 and F22; F1 and F18 remain open.

| Severity | Count |
| --- | --- |
| High and medium | 6 |
| Low | 12 |
| Hygiene | 5 |
| Malicious / exfiltration | 0 |

## The safety case

Why the app is trustworthy despite the permissions it asks for.

- **No `INTERNET` permission in any of the four module manifests.** This is the load-bearing
  property of the whole review: an accessibility service that can read window content is only as
  dangerous as its ability to transmit, and this one has none.
- **No HTTP client, analytics or ad SDK anywhere.** The only URLs in the source are user-tapped
  browser intents — GitHub, Discord, the donate page, Play Store.
- **No obfuscated payloads or dynamic code loading.** No `DexClassLoader`, no long base64 literals,
  no decode-and-execute. Reflection is confined to the documented Shizuku and HiddenApiBypass paths.
- **`FLAG_SECURE` on both lock-screen paths** (`LockScreenOverlayManager.kt:222`,
  `PasswordOverlayScreen.kt:126`). PIN entry cannot be screenshotted or screen-recorded.
- **The Gradle wrapper is the stock wrapper.** 34 entries, all `org/gradle/*`, nothing injected.
- **No secrets in the tree or in git history.** CI signing takes its keystore from injected
  environment secrets, and GitHub withholds those from fork pull requests.
- **The automation receiver is soundly built.** The component stays disabled until opt-in, the token
  is 192 bits of `SecureRandom`, comparison is constant-time, and enabling protection fails closed
  by dropping every unlock state.

## Permission inventory

The privileged half of the manifest, and the one line that makes the rest defensible.

| Permission | What it buys |
| --- | --- |
| `BIND_ACCESSIBILITY_SERVICE` | Primary lock backend. Configured with `canRetrieveWindowContent` and `flagRetrieveInteractiveWindows` — the broadest observational power on Android. |
| `SYSTEM_ALERT_WINDOW` | Draws the lock overlay above the locked app. This is what Play Protect objects to. |
| `QUERY_ALL_PACKAGES` | Enumerates installed apps for the lock list. Policy-sensitive on Play, unremarkable on F-Droid. |
| `PACKAGE_USAGE_STATS` | Alternate foreground-app detection backend. |
| `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | Keeps the lock backend alive across Doze and task killers. |
| `RECEIVE_BOOT_COMPLETED` | Restarts protection after reboot. |
| `USE_BIOMETRIC`, `VIBRATE`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `HIDE_OVERLAY_WINDOWS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Routine, and each is used for the obvious thing. |
| **`INTERNET` — not declared** | Absent from the app module and from all three library modules. Nothing observed can be transmitted. Any future dependency that introduces it invalidates this entire review. |

## Findings

| ID | Severity | Finding | Status |
| --- | --- | --- | --- |
| F1 | High/medium | Credentials are eligible for cloud backup | Open |
| F2 | High/medium | The unlock pattern is stored in cleartext | Fixed in #7 (`a5d4dee`) |
| F3 | High/medium | Password hashing has no key stretching | Fixed in #7 (`1c17e43`), with a Keystore-bound HMAC rather than stretching |
| F4 | High/medium | No rate limiting on unlock attempts | Fixed in #6 |
| F5 | High/medium | Weak biometrics are accepted | Fixed in #5 (`4541a14`) |
| F6 | Low | Protection state is broadcast to every app on the device | Open |
| F7 | Low | Rejection reasons leak configuration to unauthenticated callers | Open |
| F8 | Low | The in-app shield toggle does not re-lock on enable | Fixed in #5 (`10d89dd`) |
| F9 | Low | Anti-uninstall detection has two conditions that can never match | Partly: both casing bugs are gone (`907ddac`, `5d9935c`); the package-installer check is unchanged and was not re-verified |
| F10 | Low | Uninstall blocking targets the wrong user | Open |
| F11 | Low | Non-null assertion on a nullable framework field | Fixed in `5d9935c` |
| F12 | Low | Blocking sleeps on the main thread | Open |
| F13 | Low | Log retention does not match its documentation | Open |
| F14 | Hygiene | 110 MB of history from committed APKs | Open |
| F15 | Hygiene | IDE state tracked against its own ignore rule | Open |
| F16 | Hygiene | Empty ProGuard rules with minification enabled | Open |
| F17 | Hygiene | CI actions pinned to tags, and every branch builds twice | Open |
| F18 | High/medium | Anti-uninstall can be bypassed without the PIN | Open; a fix was considered and dropped as too complex |
| F19 | Low | Granting device admin crashes the app | Fixed in #5 (`fcae9e7`) |
| F20 | Low | Accessibility-settings detection only works in English | Fixed in `907ddac`; #5 closed the gap it left |
| F21 | Low | Manually added packages show as protected but are never blocked | Open |
| F22 | Low | The device-admin guard blocks every app's admin screens | Fixed in `5d9935c` |
| F23 | Hygiene | Unused admin-verification code | Open |

Every finding marked Open was re-checked against the code on 2026-09-11 and still holds.

### High and medium

#### F1 — Credentials are eligible for cloud backup

`AndroidManifest.xml:32,34,36` · `res/xml/backup_rules.xml` · `res/xml/data_extraction_rules.xml`

The manifest sets `allowBackup="true"`, and both rules files it points at are unedited Android
Studio templates with every rule commented out. So `app_lock_prefs` — password hash, unlock pattern
and automation token — is carried into Google cloud backup and device-to-device transfer. For an app
whose entire purpose is keeping a credential on one device, that is the wrong default.

**Fix:** exclude both preference files explicitly in `data_extraction_rules.xml` and
`backup_rules.xml`, or set `allowBackup="false"`.

#### F2 — The unlock pattern is stored in cleartext

`PreferencesRepository.kt:48` · `PreferencesRepository.kt:56-58`

`setPattern` writes the raw pattern string to SharedPreferences, and `validatePattern` compares it
with `==`. PINs and alphanumeric passwords get a salted hash; patterns get nothing at all. Combined
with F1, a pattern lock leaves the device in plaintext.

**Fix:** route patterns through the same `SecurityUtils` hash path as passwords, with a migration
for existing users mirroring the one already in `validatePassword`.

#### F3 — Password hashing has no key stretching

`SecurityUtils.kt:10` · `SecurityUtils.kt:59-73`

Hashing is a single round of salted SHA-256. The salt is correct — 16 bytes from `SecureRandom`, per
password — and it defeats rainbow tables, but it does nothing about speed. Against a 4-6 digit PIN
the entire keyspace falls in well under a second on commodity hardware once the hash is in hand. F1
is what puts it in hand.

**Fix:** PBKDF2, scrypt or Argon2 with a calibrated iteration count, keeping the existing
`salt:hash` envelope and versioning the prefix so old hashes upgrade on next successful unlock.

#### F4 — No rate limiting on unlock attempts

`features/lockscreen/` · `features/admin/AdminDisableActivity.kt`

There is no attempt counter, backoff or lockout anywhere in the lock screen, the admin-disable
screen or the set-password flow. A 4-digit PIN with unlimited guesses is 10,000 taps from the front,
no extraction required — and the same screen guards disabling anti-uninstall.

**Fix:** a persisted failure counter with escalating delay, shared by every entry point that calls
`validatePassword` or `validatePattern`.

#### F5 — Weak biometrics are accepted

`PasswordOverlayScreen.kt:256-260`

The prompt allows `BIOMETRIC_WEAK or BIOMETRIC_STRONG` with `setConfirmationRequired(false)`. On many
devices the weak class is 2D face unlock, which is photo-spoofable — an easier path past the lock
than the PIN behind it. An earlier commit in this repo is titled "Enable Strong Biometric
Authenticators".

**Fix:** request `BIOMETRIC_STRONG` only, and fall back to the PIN where the device cannot supply it.

### Low

#### F6 — Protection state is broadcast to every app on the device

`AutomationReceiver.kt:117-124`

`PROTECTION_STATE` is sent as an implicit broadcast with no permission attached, so any installed app
can register a receiver and learn the moment protection goes off — which, given the intended Wi-Fi
and location triggers, is also a presence signal. The design note explains why it is implicit and the
reasoning holds; the cost is simply worth stating.

#### F7 — Rejection reasons leak configuration to unauthenticated callers

`AutomationReceiver.kt:81-84`

`reject()` returns its reason as `resultData` to ordered senders, distinguishing "automation control
is off" from "no token has been generated" from "invalid or missing token". An app with no token
learns your configuration. The token itself is 192 bits and not brute-forceable, so this is
disclosure, not bypass.

#### F8 — The in-app shield toggle does not re-lock on enable

`MainScreen.kt:167-170` · `AutomationReceiver.kt:65-72`

The automation path calls `clearAllUnlockStates()` when protection returns, so nothing left unlocked
stays open. The shield toggle on the main screen writes the same flag but skips that step. Toggle off
then on in the UI and an already-unlocked app remains accessible — the same sequence, two different
security outcomes.

#### F9 — Anti-uninstall detection has two conditions that can never match

`AppLockAccessibilityService.kt:406` · `AppLockAccessibilityService.kt:423-424`

Both lowercase the haystack and then search for a capitalised needle —
`.lowercase().contains("App Lock")` and `.lowercase()?.contains("Device admin app")`. The
package-installer dialog check and the device-admin content-description check are therefore dead. The
two `SubSettings` checks above them do not lowercase and do work, so the feature partly functions,
but it is weaker than it reads.

#### F10 — Uninstall blocking targets the wrong user

`core/utils/Shizuku.kt:12` · `core/utils/Shizuku.kt:16`

`Process.myUserHandle().describeContents()` is the Parcelable contract method — it returns 0, not a
user id. On a single-user device that is accidentally correct; under a work profile or secondary
user, anti-uninstall is silently applied to the wrong user.

#### F11 — Non-null assertion on a nullable framework field

`AppLockAccessibilityService.kt:428`

`event.className!!` throws when an accessibility event carries no class name. The outer handler
catches and logs it, so there is no crash — but the device-admin page check silently returns nothing
for those events, which is precisely when anti-uninstall is meant to fire.

#### F12 — Blocking sleeps on the main thread

`AppLockAccessibilityService.kt:346` · `AppLockAccessibilityService.kt:443`

`Thread.sleep(200)` and `Thread.sleep(100)` run inside main-thread handlers between global actions.
ANR risk on a slow device, at exactly the moment the app is trying to assert control.

#### F13 — Log retention does not match its documentation

`LogUtils.kt:162`

The variable is named `threeDaysAgo`, the KDoc says three days, the log messages say three days, and
the value is `minus(7, ChronoUnit.DAYS)`. Audit logs live twice as long as documented.

### Hygiene

#### F14 — 110 MB of history from committed APKs

`app/debug/app-debug.apk` · `app/release/app-release.apk`

Seven debug builds at roughly 20 MB each, plus eight release builds, were committed and later
deleted. They are gone from `HEAD` but remain in history, so `.git` is 110 MB against a working tree
of about 3 MB. Every clone pays for it. Rewriting history fixes it but breaks existing forks — a
judgement call, not a defect.

#### F15 — IDE state tracked against its own ignore rule

`.idea/misc.xml` · `.gitignore`

`.gitignore` lists `/.idea`, but `misc.xml` was committed before the rule existed and is still
tracked, so the rule does not apply to it.

#### F16 — Empty ProGuard rules with minification enabled

`app/proguard-rules.pro` · `app/build.gradle.kts:41-52`

All four rules files contain only the default comments while release builds run `isMinifyEnabled` and
`isShrinkResources`. Nothing is broken today, but the AIDL interfaces and hidden-API stubs are exactly
the surface R8 tends to strip in ways that surface only at runtime on a user's device.

#### F17 — CI actions pinned to tags, and every branch builds twice

`.github/workflows/android.yml`

All five actions are pinned to version tags rather than commit SHAs — a tag can be moved. The signing
path itself is sound: the keystore is decoded from secrets into `RUNNER_TEMP`, and GitHub withholds
secrets from fork pull requests, so the release key is not reachable from an untrusted PR.
Separately, `on: push` plus `pull_request` means each branch builds twice.

## Anti-uninstall follow-up

A second pass over the anti-uninstall path alone, at `f5aa5a3` on `master`: the accessibility
service's detection and blocking, the device admin receiver, the admin-disable flow, and the Shizuku
uninstall block on the Anti-Uninstall screen. Line numbers in this section are against that commit.

The feature is two separate mechanisms. The settings toggle relies on Android refusing to uninstall
an app whose device admin is active. The system enforces that, but the admin can be switched off
from Settings, and the only thing guarding that switch is the accessibility service recognising the
page and pressing Back, Home and Lock Screen. The Anti-Uninstall screen instead calls
`setBlockUninstallForUser` through Shizuku, which the package manager enforces across reboots and in
safe mode. It covers only the apps switched on there — App Lock is not one of them by default — and
anyone with ADB or Shizuku can reverse it.

### High and medium

#### F18 — Anti-uninstall can be bypassed without the PIN

`AppLockAccessibilityService.kt:408-488` · `AppLockAccessibilityService.kt:561-563` · `res/values/strings.xml:169` · `README.md:69-70`

Three routes never meet the accessibility guard:

- **Safe mode.** No third-party code runs, the accessibility service included. Deactivate the device
  admin in Settings, then uninstall.
- **Force stop.** On stock Android, force-stopping an app also removes it from the enabled
  accessibility services. No check is aimed at App info or its confirmation dialog, and the Shizuku
  re-enable in `onUnbind` cannot run once the process is dead.
- **ADB.** Either `am force-stop` or `settings put secure enabled_accessibility_services` does the
  same without touching the Settings screens being watched.

Where the guard does fire, it fires after the page is already on screen, and locking the phone stops
no one who knows its PIN from unlocking and trying again. The settings text promises to "Prevent
unauthorized uninstallation of App Lock" and the README lists anti-uninstall protection as a
feature; against anyone who knows these routes, neither holds.

**Fix:** only a Device Owner can close these routes, with `setUninstallBlocked` and the
`DISALLOW_SAFE_BOOT`, `DISALLOW_APPS_CONTROL` and `DISALLOW_DEBUGGING_FEATURES` restrictions. That
means provisioning on a device with no accounts, or QR or MDM enrolment — a heavy ask for this app.
Short of that, describe the feature in the settings text and README as a deterrent, not prevention.

### Low

#### F19 — Granting device admin crashes the app

`core/broadcast/DeviceAdmin.kt:23`

`onEnabled` calls `DevicePolicyManager.setUninstallBlocked`, which only a device owner or profile
owner may call. App Lock is neither, so the call throws `SecurityException`, nothing catches it, and
the process crashes each time the user grants device admin. The call has never blocked anything:
what stops uninstall is the admin being active, which the system records before `onEnabled` runs.
The `anti_uninstall` flag written just above uses `apply()` and may not reach disk before the crash.

#### F20 — Accessibility-settings detection only works in English

`AppLockAccessibilityService.kt:434,436` · `AndroidManifest.xml:97` · `res/values-ar/strings.xml:3`

Both accessibility-settings checks search for the literal `"App Lock"`, case-sensitively, but the
name Settings shows comes from `@string/app_name`. The one shipped translation, Arabic, renders it
as "قفل التطبيقات", so on an Arabic-locale device neither check can match. The device-admin check
keys on class names and still works, but it runs inside the accessibility service — and the page for
switching that service off is the one left unguarded.

#### F21 — Manually added packages show as protected but are never blocked

`features/antiuninstall/ui/AntiUninstallScreen.kt:142-157` · `features/antiuninstall/ui/AntiUninstallScreen.kt:159-170`

`addManualPackage` saves the package and shows its switch on, but never calls
`blockUninstallForUser`; only `toggleAppProtection` does. A manually added package stays
uninstallable until it is toggled off and on again. `toggleAppProtection` also saves before calling
Shizuku, so if that call throws, the app crashes with the package already recorded as protected.

#### F22 — The device-admin guard blocks every app's admin screens

`AppLockAccessibilityService.kt:455-464` · `AppLockAccessibilityService.kt:472`

`isDeviceAdminPage` matches on class name alone, and `blockDeviceAdminDeactivation` checks only that
App Lock's own admin is active, not which admin the page belongs to. While anti-uninstall is on,
opening the device-admin list, or any other app's activation or removal screen, sends the user to
the lock screen.

### Hygiene

#### F23 — Unused admin-verification code

`core/broadcast/DeviceAdmin.kt:33-35` · `ui/components/AdminPasswordVerificationDialog.kt` · `features/admin/AdminDisableActivity.kt:77,115,153`

`AdminDisableActivity` calls `setPasswordVerified` in seven places and nothing reads the flag.
`AdminPasswordVerificationDialog` is never used, and `DeviceAdmin` does not override
`onDisableRequested`. Disabling anti-uninstall in the app only clears the `anti_uninstall` flag; the
device admin stays active until it is removed in Settings. Wiring this up would not close F18:
`onDisableRequested` can show a warning but cannot refuse.

### Earlier findings at this commit

F9–F12 all still hold at `f5aa5a3`. F10 has not moved; the other three now sit at:

| ID | Location at `f5aa5a3` |
| --- | --- |
| F9 | `AppLockAccessibilityService.kt:439,456-457` |
| F11 | `AppLockAccessibilityService.kt:461` |
| F12 | `AppLockAccessibilityService.kt:368,476` |

F9 also understates its first case. Fixing the casing would not revive the package-installer check
at `AppLockAccessibilityService.kt:437-439`: detection only runs for events from
`com.android.settings` (`AppLockAccessibilityService.kt:120-121`), and that check requires
`com.google.android.packageinstaller`.

## One behavioural caveat

With anti-uninstall enabled, opening accessibility settings or the device-admin page triggers
`GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME` and then `GLOBAL_ACTION_LOCK_SCREEN` — the app locks the
phone to stop you reaching the switch. That is the advertised feature working as designed, and it is
the right shape for a parental-control or shared-device threat model — though, as F18 shows, not a
barrier within one. It can also trap a user who legitimately wants out, and it is a far more likely
explanation for Play Protect's warning than the overlay permission the README blames.

## Scope and method

- Static review of the complete tree at `8655d0a`.
- **Follow-up:** the anti-uninstall path was re-read in full at `f5aa5a3`, also statically. Claims
  about platform behaviour — safe mode, force stop, the Shizuku uninstall block persisting — follow
  from stock Android and were not reproduced on a device.
- Git history was scanned for committed secrets and binaries; the Gradle wrapper JAR was opened and
  its entries enumerated.
- **Not covered:** the project was not built or run. No dynamic analysis, no instrumented testing, no
  APK inspection.
- **Not covered:** third-party dependencies — Shizuku, hiddenapibypass, AndroidX, Compose — resolve
  as binaries at build time from Maven Central and Google's repository, and were not examined.
- The absence of `INTERNET` is the assumption the safety case rests on. It holds at this commit
  across all four modules; a dependency that introduces it later would need this review redone.
