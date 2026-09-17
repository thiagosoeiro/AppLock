# Security fixes

How the findings in `SECURITY_AUDIT.md` were fixed: three PRs, each built by CI and tested on a phone
before merging, a fourth after anti-uninstall was beaten on the phone, a fifth after locked apps
opened freely on the phone, a sixth after a notification brought up lock screens, and a seventh after a Secure Folder copy kept locking with no row to unprotect it. The audit's findings table records the status of every finding,
including the ones left open.

In scope: F2, F3, F4, F5, F8, F19, F20 and F22. F1 and F18 were not taken on; F18 was considered and
dropped as too complex. Chunk 4 came later: it fixes F9 and part of F12, and narrows F18 without
closing it. Chunks 5, 6 and 7 fix bugs found in use, not audit findings.

## Status

| Chunk | Findings | Branch | PR | CI | On device | Merged |
| --- | --- | --- | --- | --- | --- | --- |
| 1 — Quick fixes | F5, F8, F19, F20 | `fix/security-quick-fixes` | [#5](https://github.com/thiagosoeiro/AppLock/pull/5) | green (`8aff4bd`) | done | `1a77897` |
| 2 — Rate limiting | F4 | `fix/security-rate-limiting` | [#6](https://github.com/thiagosoeiro/AppLock/pull/6) | green (`8f790a9`) | LGTM | `fa1e06b` |
| 3 — Credential storage | F2, F3 | `fix/security-credential-storage` | [#7](https://github.com/thiagosoeiro/AppLock/pull/7) | green (`1c17e43`) | LGTM | in #7 |
| 4 — Anti-uninstall lock speed | F9, F12 (part), F18 (narrowed) | `fix/anti-uninstall-lock-speed` | [#13](https://github.com/thiagosoeiro/AppLock/pull/13) | green (`cafddd4`) | 3 rounds | `9e2387c` |
| 5 — Interrupted biometric prompt | — (found in use) | `fix/interrupted-biometric-prompt` | [#23](https://github.com/thiagosoeiro/AppLock/pull/23) | green (`442a865`) | 7 rounds, 1 check pending | 2026-09-16 |
| 6 — Notification taken for an app switch | — (found in use) | `fix/notification-switch` | [#24](https://github.com/thiagosoeiro/AppLock/pull/24) | green (`5ea79ee`) | 1 round | 2026-09-16 |
| 7 — Secure Folder copy with no row | — (found in use) | `fix/secure-folder-locks` | [#25](https://github.com/thiagosoeiro/AppLock/pull/25) | pending | pending | — |

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
  change without a reboot. Follow-up in `55a7e2c`, with the Brazilian Portuguese translation: once
  the app can have its own language (Android 13+), the name the service reads can differ from the
  one Settings shows, so `ownLabels` holds the name in both the app's and the phone's language.
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

## Chunk 4 — Anti-uninstall lock speed (done)

Not from the original scope. On 2026-09-12, with anti-uninstall on, device admin was deactivated and
the app uninstalled on the phone, because the screen locked too late. PR #13, one commit per change,
merged as `9e2387c`.

- **Why it lost.** Every lock came from the accessibility service reading a page once it was on
  screen:
  - events were held 100 ms, and could be dropped;
  - a page not drawn yet was missed for good;
  - the device admin block waited 100 ms, and did nothing once the admin was gone;
  - `DeviceAdmin.onDisabled` cleared `anti_uninstall`, which switched every guard off.
- **One way to lock** (`96468cf`). `PhoneLocker` tries the accessibility lock action, then
  `DevicePolicyManager.lockNow()`, and logs which one locked and why.
- **Admin callbacks** (`d631714`). `onDisableRequested` locks on the Deactivate tap and returns the
  disguised admin description as a warning, so removal needs OK on a second prompt. `onDisabled`
  locks, which for App info's one-tap "Deactivate and uninstall" happens before Android stops the
  app, and leaves the flag on. Turning anti-uninstall off with the PIN clears the flag first.
- **Force-lock** (`639afa7`). A new admin policy. Android keeps the policies granted at activation,
  so the main screen and the Settings switch ask once for the admin again; for an active admin the
  grant page only adds the policy. The guard leaves that page alone for a minute after the app opens
  it, while the policy is still missing.
- **Accessibility turned off** (`cf40ea4`). `onUnbind` locks through device admin, since Android has
  already dropped the service's connection by then.
- **Faster screen checks** (`462f341`).
  - No event delay; this also reaches locked-app detection.
  - Settings pages are checked as they open, at 150 and 400 ms and after content changes, for up to
    a second, reading the window once per check.
  - Blocks go Back, lock, Home, with no pause (part of F12).
  - The uninstall-dialog check can run again (F9), but only when an uninstall screen and our name
    appear within 3 s of each other, so installing an update isn't blocked.
- **First phone test** (2026-09-12, Galaxy S24 Ultra, One UI 8.5):
  - The device admin page (`SecDeviceAdminAdd` on One UI) locked 8–14 ms after it opened, and the
    Accessibility page 5–9 ms after, so the Deactivate button and the switch were never reachable.
    The Deactivate-tap, admin-removed and accessibility-off locks therefore didn't get to run.
  - App info → Uninstall went through the device admin page, which locked. An uninstall started
    elsewhere was refused by Android because the admin was active.
  - The uninstall-dialog check missed: on One UI the installer's dialog event comes about 450 ms
    before its uninstall screen.
  - The Accessibility page locked twice each time, because Settings reports it twice.
  - A locked app once opened its lock screen 4 times at once, with 4 fingerprint prompts.
  - App info didn't bounce when opened: One UI keeps the version off screen, so that check never
    matches, a limit since `5d9935c`.
- **Changed after the test**, one commit each:
  - the uninstall screen and our name count in either order, within 3 s;
  - a guard ignores repeat matches for 2 s after locking, or until the phone is unlocked;
  - a lock screen claims its flag before opening, so a burst of events opens one;
  - clearing unlock state does nothing, and logs nothing, when nothing is unlocked;
  - Settings pages showing our name log their view IDs, to find a way to recognise App info on
    One UI.
- **Second phone test** (2026-09-14, same phone): the Accessibility page locked once, a locked app
  opened one lock screen, the apps list didn't lock, and "Cleared all unlock states" fell from 711
  lines to 4. App info still only bounced after the version scrolled in, but the view-ID log showed
  its header (`entity_header_title`) and `uninstall_button`, which the apps list lacks.
- **Changed after the second test**, one commit: App info is recognised by our name in that header
  next to an Uninstall button, as well as by name-plus-version, so it bounces on One UI without a
  scroll.
- **Third phone test** (2026-09-14): App info locked 42 ms after the page opened, with no scroll,
  and the apps list still didn't lock. The uninstall dialog locked as well, confirming the
  either-order match, and the device admin page locked twice.
- **Device admin locking is proven** (2026-09-14). Every lock until then had gone through the
  accessibility service, so the device admin path had never been seen working — and it is the only
  path left once the accessibility service is going away. Settings → Advanced → **Test screen lock**,
  which appears while Logging is on, calls that path on its own, and the log answered: "Locked the
  phone through device admin: test from settings". Still unverified: that `onUnbind` fires when the
  service is switched off (the volume-key accessibility shortcut would show it, since it never opens
  the guarded page), reinstalling over the app, and the Deactivate-tap and admin-removed locks.
- **Known gap, left as is.** After a guard locks, repeat matches are ignored for 2 s, so that
  Settings reporting one page twice doesn't lock twice. That window is cleared when the phone is
  unlocked — but the reappearing page and the unlock broadcast race each other, and if the page
  wins it is taken for a duplicate and suppressed, leaving a second or two on that page unguarded.
  In testing this made the Accessibility page's switch reachable once. Judged low priority: it only
  helps someone who already knows the phone's PIN, who can get through every layer anyway (F18),
  and device admin still blocks uninstall and still locks from the admin callbacks. The fix, if it
  is ever worth it: also clear the window when the screen turns off, which our own lock causes at
  once, so only the duplicates arriving before the screen is off are swallowed.

## Chunk 5 — Interrupted biometric prompt (done, one check pending)

Not from the audit. On 2026-09-16 a locked app showed its lock screen for a moment and then opened,
and after that every locked app opened without authentication until the screen went off. The bug
came in with the auto-prompt (`64fbb7d`). One commit per change.

- **Why it failed.** The log showed the same sequence twice:
  - the lock screen opened and raised the fingerprint prompt, which took the lock screen down once
    it was on screen;
  - about 0.9 s later the app opened its next screen over the prompt, and Android cancelled it;
  - the biometric library passes errors on only while the prompt's activity is started, so the
    fallback to the lock screen never ran. While the activity lived, the service believed an
    authentication was still in flight and skipped every locked app without a log line;
  - once Android destroyed the activity, that was cleared but the lock screen flag was not, so
    every locked app was skipped as "lock screen already shown" until the screen went off.

  Pressing Home, opening Recents or taking a call over the prompt leaves it the same way.
- **Release** (`e518cb6`). A prompt that leaves the screen without an answer clears the in-flight
  state, cancels itself, finishes, and, if it had taken the lock screen down, clears the flag and
  tells the service. That covers onStop, a destroy that skipped onStop, and an error while not
  resumed. Success and the hand-back to the lock screen settle the session first, so they are
  unchanged, and config changes are skipped.
- **Re-prompt once** (`4dd2f8c`). The returning lock screen raises the prompt again. Once the same
  app's prompt has been interrupted twice with gaps under 10 s, its lock screen waits for a tap on
  the fingerprint icon or the PIN, so an app that keeps covering the prompt can't loop it.
- **Check again** (`3daf5ab`). 300 ms after an unanswered prompt, the app in front is checked again
  if it is the one the prompt was for, or one skipped while the prompt was up, so an app that sits
  still is locked without waiting for its next event. The wait lets Home or Recents report the
  launcher first.
- **First phone test** (2026-09-16, same phone):
  - Three covered prompts on two apps each logged "went away unanswered", and the lock screen was
    back within 46–108 ms, prompted again, and unlocked. Other locked apps kept locking.
  - Cancelling the prompt brought the lock screen back without prompting.
  - Not reached: Home, Recents or screen off on the prompt, and the two-interruption limit.
  - Found: One UI draws the fingerprint prompt from its own package, which wasn't excluded. An app
    inside Secure Folder looped seven of our prompts in 18 s: a fingerprint prompt over it, taken
    at the time for the app's own lock but more likely Secure Folder's, counted as leaving the
    app, and the check after an unanswered prompt looked at the prompt itself. The same code runs
    without this PR, so the loop wasn't caused by it.
- **Changed after the test** (`a4a580b`): `com.samsung.android.biometrics.app.setting` is excluded
  like System UI.
- **Second phone test** (2026-09-16, same phone): unlocks outside Secure Folder were clean, and the
  fingerprint prompt no longer counted as leaving. An app inside Secure Folder still locked again
  five times in 25 s: after each unlock, a Secure Folder window, sometimes 40 ms later, counted as
  switching to another app. Home, Recents, screen off and the limit were still not reached.
- **Changed after the second test** (`e6851f3`): Secure Folder counts as a neutral surface, like the
  launcher, so leaving an unlocked app for it holds the unlock for the 5 s return window. It is not
  excluded, so it still locks if it is in the list. The switch log line names the window class and
  event type.
- **Third phone test** (2026-09-16, same phone, a day of normal use):
  - Inside Secure Folder, each app locked once. Secure Folder's own events came 5–15 s after the
    unlocks, all content changes in the background, and each was held and let go without a second
    lock.
  - Two real covered prompts on one app, 0.75 s and 0.87 s after they appeared: the lock screen was
    back within 58–88 ms and prompted again, and the check 300 ms later added no second lock screen.
    Fingerprint unlocked it, and the next locked app locked as usual.
  - Cancelling the prompt brought the lock screen back without prompting, as before.
  - Still not reached: Home, Recents or screen off on the prompt, and the two-interruption limit.
- **Fourth phone test** (2026-09-16, same phone):
  - Power button on the prompt, three times: the app locked again once the phone was unlocked. One UI
    cancels the prompt at the key press, while the prompt's activity is still in front, so the lock
    screen came back just before the screen went dark and then stayed over the phone's own lock
    screen. Screen-off has never taken the lock screen down; that is older than this PR and left for
    now.
  - Home on the prompt: the lock screen came back over the home screen. One UI reported the cancel
    as the user's while the activity was still in front, so it was handled like Back.
  - The two-interruption limit was not reached.
- **Changed after the fourth test** (`fcc9c8b`): a cancel that arrived while the prompt's activity
  was still in front waited up to 1 s, returning to the lock screen once window focus came back and
  releasing the prompt if the activity was paused first.
- **Fifth phone test** (2026-09-16, same phone): it didn't work. Of 16 cancels, only two were
  released; the other 14 went back to the lock screen. One UI hands the activity its focus back about
  20 ms after a cancel, while leaving for Home stops the activity only about 0.9 s later.
- **Changed after the fifth test** (`1e782be`): a cancel returns to the lock screen at once again,
  and the prompt's activity stays under it for up to 3 s. If the activity is stopped in that time,
  by Home, Recents, another app or the screen going off, the lock screen is taken down and the
  prompt counts as one that went away unanswered. After Back the activity isn't stopped, so the lock
  screen stays. The watch ends early once the lock screen is unlocked or closed.
- **Sixth phone test** (2026-09-16, same phone):
  - Home on the prompt, six times: each time the activity stopped 0.86–0.95 s after the cancel, the
    lock screen was taken down, and the app locked again when reopened.
  - Home twice within 10 s, done twice: the next lock screen waited for a tap both times.
  - Back on the prompt: the activity was not stopped, and the lock screen stayed.
  - Power button: both times the screen went off, a cancel had already put the lock screen back
    3–17 s earlier, so it stayed over the phone's own lock screen. That is the older screen-off
    behaviour, not the prompt.
- **Changed after the sixth test**, two older behaviours, each in its own commit:
  - `f794f05`: screen-off cleared the lock state but left the lock screen up. It is now taken down
    at screen off, or at screen on if the phone locked later, but only while the phone's secure
    lock is on. The app locks again from its own events once the phone is unlocked. With a lock
    delay or no secure lock, the lock screen stays up as before, since nothing else covers the app.
  - `442a865`: Back and the back gesture did nothing on the lock screen. The key reached its window
    unhandled, because the lock screen's back handler only hears from an activity. The window now
    passes Back on, so it closes the lock screen like the close button (Android 9 and later).
- **Seventh phone test** (2026-09-16, same phone):
  - Power button on the prompt, on two apps: the lock screen came down 0.4 s after the cancel and
    before the screen went off; each app locked again once the phone was unlocked.
  - Back gesture on the lock screen, twice: it closed 0.2 s later, like the close button.
  - Home on the prompt still takes the lock screen down, and two in 10 s still make the next one wait
    for a tap. An app covering its own prompt was handled as before.
  - Not reached: the lock screen up for more than 3 s when the screen goes off. Both times power was
    pressed on a returned lock screen, it was within 3 s of the cancel, so the watch took it down
    first.
- **Merged** on 2026-09-16 in PR #23 after the seventh test, with one check left for later.
- **Pending check** for `f794f05`, the screen-off takedown. On a locked app, tap "Use PIN" (or use a
  lock screen that waits for a tap), wait about 5 s, press the power button, then turn the screen
  on. Expected: the phone's own lock screen with no app lock screen over it, and the app locks again
  once the phone is unlocked. With Logging on, the lock screen's window should be removed at
  "Screen off detected" with no "Left the lock screen" line before it.

## Chunk 6 — Notification taken for an app switch (done)

Not from the audit. On 2026-09-16 a lock screen came up over an unlocked app while it was in use,
with nothing touched. It was the lock screen of a messaging app, and once that was unlocked the app
in front locked again too. A test text reproduced it twice.

- **Why it happened.** Both times the log read "Switched from unlocked app … to <messaging app>
  (android.widget.FrameLayout, TYPE_WINDOW_CONTENT_CHANGED)", with no screen of that app opening.
  System UI draws notifications, but Android builds a notification's views with the context of the
  app that posted it (`RemoteViews`' context wrapper returns that app's package, and `View` stamps
  it on its events). The service listens to every interactive window, so the notification looked
  like switching to the messaging app: its lock screen came up, and the app in front lost its unlock.
  Per the code, a notification from an app that isn't locked ended the unlock of the app in front
  the same way.
- **Fix** (`e633e3a`). When an event's package differs from the last one, its window is looked up.
  A system-type window whose root view belongs to System UI is ignored, with an "Ignored … drawn by
  System UI" line. A window that isn't found, an app window, or a floating window an app draws
  itself, like a chat head, is handled as before, so a lock is never skipped on a guess.
  Anti-uninstall checks run earlier and are unchanged. Notification rows in the pulled-down shade
  come from the same System UI window, so they are ignored too.
- **First phone test** (2026-09-16, same phone):
  - A text over an unlocked app logged one "Ignored" line and brought up no lock screen. The app
    stayed open.
  - Locked apps still locked when opened, 14 times on four apps, including going straight from one
    locked app to another. A return from Home within 5 s kept its unlock.
  - With the shade most likely pulled down, 18 events from a Samsung system component inside the
    System UI window were ignored. One more, as the screen turned off, was not: it only ended the
    unlock of the app in front, which screen-off ends anyway, and that component isn't locked.
  - Not confirmed: that the messaging app's lock screen in the test came from tapping its
    notification, and closing the shade with the app kept in front. Not tested: bubbles.
- **Merged** on 2026-09-16 in PR #24 after the first test.

## Chunk 7 — Secure Folder copy with no row (in testing)

Not from the audit. On 2026-09-16 a protected app was uninstalled outside Secure Folder. Its row left
the main screen, but its copy inside Secure Folder still got the lock screen, and nothing was left to
unprotect it. `FUTURE_IMPROVEMENTS.md` item 18 has the findings and the options weighed.

- **Why it happened.**
  - The lock list holds package names, and the services check the name alone, so every copy locks.
  - The main screen built rows only for names `getApplicationInfo(name, 0)` finds, and that sees only
    apps outside Secure Folder.
  - Nothing removed an uninstalled app from the list.
- **Why the copies aren't told apart.** Nothing this app can call without extra permissions sees
  inside Secure Folder:
  - Android 16 stopped `MATCH_UNINSTALLED_PACKAGES` from reaching other users.
  - One UI 8 reportedly makes Secure Folder a hidden private profile.

  So protecting a package still protects every copy, as before. The fix makes that lock visible and
  keeps the list accurate.
- **Fix, part 1** (`e2cdd7c`).
  - Every protected package gets a row. One not installed outside Secure Folder shows its saved name,
    or its package name, with Android's generic icon and "Not installed outside Secure Folder".
  - Unprotecting works as before.
  - Names are saved in their own prefs file, `locked_app_names`, while apps are installed, and
    dropped on unprotect.
  - A log line names each such row.
- **Fix, part 2** (`47d6cb5`).
  - `PackageRemovedReceiver` handles `ACTION_PACKAGE_FULLY_REMOVED` and reads the hidden extra
    `REMOVED_FOR_ALL_USERS`. Only true removes the entry.
  - False, meaning a copy remains, as in Secure Folder, keeps the entry. So does a missing extra.
  - Each case logs a line, so a lock is never dropped on a guess.
- **Limits.**
  - An app installed only inside Secure Folder can't be added from "+". Unprotecting one lasts until
    it is installed outside again.
  - A Secure Folder copy uninstalled later sends the main user no broadcast, so its entry stays
    listed.
  - Entries left over from before this change stay listed until unprotected.
- **Phone test:** pending. PR #25 lists the checks.

## Testing chunks 2 and 3

Install over the current build with your real PIN and pattern, with anti-uninstall **off and device
admin removed**. An active admin greys out Clear storage and the App info guard bounces you, so a
lockout bug would leave no easy way to reset the app. Turning anti-uninstall off in the app does not
remove the admin; do that in Settings.
