# Security fixes

How the findings in `SECURITY_AUDIT.md` were fixed: three PRs, each built by CI and tested on a phone
before merging. The audit's findings table records the status of every finding, including the ones
left open.

In scope: F2, F3, F4, F5, F8, F19, F20 and F22. F1 and F18 were not taken on; F18 was considered and
dropped as too complex.

## Status

| Chunk | Findings | Branch | PR | CI | On device | Merged |
| --- | --- | --- | --- | --- | --- | --- |
| 1 — Quick fixes | F5, F8, F19, F20 | `fix/security-quick-fixes` | [#5](https://github.com/thiagosoeiro/AppLock/pull/5) | green (`8aff4bd`) | done | `1a77897` |
| 2 — Rate limiting | F4 | `fix/security-rate-limiting` | [#6](https://github.com/thiagosoeiro/AppLock/pull/6) | green (`8f790a9`) | LGTM | `fa1e06b` |
| 3 — Credential storage | F2, F3 | `fix/security-credential-storage` | [#7](https://github.com/thiagosoeiro/AppLock/pull/7) | green (`1c17e43`) | LGTM | in #7 |

F22 was already done (fixed in `5d9935c`). F20's main fix shipped in `907ddac`, and chunk 1 closed
the gap it left.

## How the chunks ran

- Each chunk branched off an up-to-date `master` once the previous one had merged. Chunks 2 and 3
  both rewrite `PreferencesRepository`, so they were not stacked.
- One commit per finding, so any one can be reverted alone. Nothing was compiled locally; CI was the
  first build.
- Each PR was tested on a phone before merging, and its plan here was then cut down to what shipped.

## Why this split and order

- Chunk 1 is close to risk-free and ships the crash fix (F19) and strong-only biometrics (F5)
  without waiting on the risky work.
- Chunks 2 and 3 can lock you out of your own apps. Separate PRs keep a lockout traceable to one
  change and revertible on its own.
- F4 before F3: F4 adds the PIN-length gate, which keeps F3's Keystore check off every Auto Unlock
  keypress.
- Against the threat this fork targets — a thief holding an unlocked phone — F4 is worth more than
  F2 and F3, which only matter once the hash is off the device.

## Chunk 1 — Quick fixes (done)

Merged in PR #5 as `1a77897`, after CI and the on-device checklist.

- **F19** (`fcae9e7`). Removed the `setUninstallBlocked` call that crashed `DeviceAdmin.onEnabled`
  (only a device or profile owner may call it); the flag is written with `commit`.
  `ANTI_UNINSTALL.md` now credits the active admin for blocking Uninstall.
- **F8** (`10d89dd`). The shield toggle and automation both call `AutomationReceiver.setProtection`,
  so turning protection back on clears unlocks and restarts the lock service from either.
- **F5** (`4541a14`). `BIOMETRIC_STRONG` only, in all three prompts and `canAuthenticateBiometrics`.
  On the S24 Ultra that means fingerprint only.
- **F20** (`0855247`). `ownLabel` is a getter read on every check, so the guards follow a language
  change without a reboot.
- **Differed from the plan.** A regression review found that the Settings Anti-Uninstall toggle read
  the flag once, which the F19 crash had hidden by restarting the app. `8aff4bd` makes
  `SettingsScreen` listen for changes to the flag, which also stops the toggle showing ON after
  protection is turned off.
- **Known edge cases, left alone.** The lock-screen biometric button still shows on phones without
  strong biometrics; its prompt falls back to the PIN. With the Usage Stats backend and usage access
  revoked, turning the shield on can crash the app, because `UsageLockService` stops before calling
  `startForeground`; automation and boot already had that path.

## Chunk 2 — Rate limiting (done)

Merged in PR #6 as `fa1e06b`, after CI; signed off as LGTM.

- **Limit** (`f83f197`). New `UnlockAttemptLimiter`, stored in `app_lock_prefs`: 4 free wrong tries,
  then 30s doubling to a 30 min cap. The deadline is on `elapsedRealtime` plus the boot count, so the
  clock in Settings can't end it and a reboot restarts it in full.
  - `PreferencesRepository.validatePassword` / `validatePattern` go through `limitAttempts`, which
    refuses everything during a wait and doesn't count misses under 4 characters. The old bodies are
    private `checkPassword` / `checkPattern`.
  - `setPassword` stores `pin_length`; a successful `checkPassword` records it for older PINs. Auto
    Unlock checks only at that length.
  - A strong biometric success calls `clearFailedAttempts()` in all three prompts.
- **Countdown** (`70efb2c`). `Lockout.kt` (`rememberLockoutSeconds`, composable `lockoutMessage`,
  `Context.getLockoutMessage`) and one English string. The PIN, pattern and password lock screens and
  both anti-uninstall-off screens show "Too many attempts. Try again in 0:30" and disable input; the
  change-PIN screens show it as a toast.
- **Differed from the plan.** `8f790a9`, from the regression review: the pattern and password screens
  clear the leftover "Incorrect …" when a wait starts or ends, and the password fields refocus so the
  keyboard comes back. `Lockout.kt` gained the composable `lockoutMessage` alongside the Context one.
- **By design, worth remembering.**
  - Waiting doesn't forgive wrong tries; only a correct entry or fingerprint does, and each miss after
    the 5th lengthens the wait. A reboot brings a full wait back if no correct entry came since, even
    one that had already run out.
  - Auto Unlock waits for → until the first PIN unlock on a chunk 2 build (fingerprint doesn't record
    the length). A wrong last digit now clears the PIN and counts.
  - The anti-uninstall-off screen has no fingerprint button, so end a wait on a lock screen first.
    Phones without strong biometrics can only wait.

## Chunk 3 — Credential storage (done)

Merged in PR #7 after CI; signed off as LGTM. The same PR added this file and the audit to the repo.

- **F2** (`a5d4dee`). `setPattern` stores a salted SHA-256, like the PIN and password. A pattern still
  stored as plain text is hashed when the app starts; if that couldn't run, a plain-text match still
  unlocks and re-stores it hashed.
- **F3** (`1c17e43`). New `CredentialHasher`. The PIN, password and pattern are stored as
  `v2:salt:mac`: an HMAC-SHA256 of the salted SHA-256, under a non-exportable Android Keystore key
  (TEE). A copied hash can only be checked on the phone that holds the key.
  - Chosen over the audit's suggested PBKDF2, which can't save a 4–6 digit PIN or a pattern: a GPU
    exhausts those in seconds to minutes at any practical iteration count.
  - The MAC covers the old hash, so existing values upgrade when the app starts, without an unlock.
    If the Keystore fails, new values fall back to `salt:hash` and older ones are still checked.
  - Credentials restored from a backup, or copied by a phone-transfer app, arrive without the key. A
    marker in `noBackupFilesDir` holding the phone's Android ID detects that, and the app erases them
    and asks for a new PIN, keeping locked apps and settings.
  - On the phone that made the key, a key that fails to load is an error and is never replaced, so no
    stored hash is orphaned and nothing is erased on a Keystore hiccup.
  - `limitAttempts` still wraps both checks, and `setPassword` still stores `pin_length`.
- **Differed from the plan.** `forgetKey()` was dropped. A key may be generated whenever the marker
  doesn't hold this phone's ID, so a marker copied from another phone can't block it.
- **By design, worth remembering.**
  - No going back: older builds can't read upgraded credentials. To roll back, unlock with
    fingerprint and use Forgot PIN on the change-PIN screen, or clear storage.
  - Keystore errors fail closed: the entry is refused and counts toward the wait. Fingerprint still
    works and clears it.
  - A key lost on the same phone erases nothing, so the PIN stops working. Keep fingerprint on;
    without it, removing device admin takes safe mode.
  - A few cheap or uncertified phones keep Keystore keys in software, which works but is weaker.
  - The missing-key path was not tested on a phone, since that needs a restore without the key.
- **Follow-ups noted, not done.** The main screen reads the protection state once, so the shield can
  still show ON after automation has turned protection off. And the log doesn't say whether a change
  came from the shield or from automation.

## Testing chunks 2 and 3

Install over the current build with your real PIN and pattern, with anti-uninstall **off and device
admin removed**. An active admin greys out Clear storage and the App info guard bounces you, so a
lockout bug would leave no easy way to reset the app. Turning anti-uninstall off in the app does not
remove the admin; do that in Settings.
