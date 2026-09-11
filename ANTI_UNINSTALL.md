# Disguise and anti-uninstall

This fork hardens AppLock against a thief who steals an unlocked phone and wants the lock gone. It
does two things: it stops looking like a lock app, and it resists the ways an app is normally
removed or stopped.

## Disguise

Outside the PIN screen the app presents itself as a system component, so a thief scanning the
launcher or Settings has nothing obvious to remove:

- **Name:** *System Services* (Arabic: *خدمات النظام*).
- **Icon:** a plain gear, matching themed icons on Android 13+.
- **Notification:** the background-service notification reads *System Services / Running*, not
  "Protecting your apps".
- **Recents:** on Android 13+ the app shows a blank thumbnail instead of your list of locked apps.

The disguise is **fixed at build time, not a setting.** Only the launcher entry could be swapped at
runtime; Settings → Apps, Accessibility, Device admin apps and the uninstall dialog all read the
name baked into the APK, which is exactly where a thief goes to remove the app. Changing the
disguise means editing `app_name` and the launcher icon and rebuilding.

Two things are deliberately left alone:

- **Screens behind the PIN** (intro, settings, dialogs) still say AppLock. You only reach them after
  unlocking, and the PIN pad itself carries no branding.
- **The package name** `dev.pranav.applock` is unchanged, so the update installs over your existing
  app and keeps its settings and permissions. It shows only in advanced App info or over `adb`.

## Anti-uninstall

Turn on **Settings → Anti-Uninstall Protection**. It needs two permissions — Device Administrator
and the Accessibility Service — and with both granted the app resists removal.

With it **on**, these are what each Settings surface does:

| On the phone, go to | What happens |
|---|---|
| Settings → Accessibility → System Services | Sent to the home screen and the screen locks |
| Settings → Apps → System Services | Bounced off the page and the screen locks |
| Settings → Apps → System Services → Uninstall | Uninstall is blocked by device admin |
| Device admin apps (path varies by phone) | Bounced off, with the toast "This action isn't allowed." |
| Ordinary pages (Wi-Fi, Display, …) | Nothing happens; they work normally |

> **It blocks you, too.** These pages bounce whoever opens them, including you. To change any of
> these settings yourself, first turn Anti-Uninstall Protection **off** — that asks for your PIN —
> and then open the page.

### How each button is covered

- **Uninstall** — blocked by Android for as long as Device Admin is active: the app can't be removed
  until its admin is turned off, and the page for turning it off bounces you (see the table above).
- **Force stop** — greyed out by Android for any app with an active device admin. It briefly becomes
  tappable only while device admin is off (for example, right after an update, before the app
  re-arms).
- **Clear data / Force stop / anything else on App info** — the accessibility service bounces you
  off the app's own App info page before you can reach them.

### Limitations

- **OEM variance.** Settings pages are named differently on each skin. The detection is tuned
  against stock Android and One UI (tested on a Galaxy S24 Ultra, One UI 8.5); another skin may
  title a page differently and slip past. If a page isn't caught on your phone, enable
  **Settings → Advanced → Logging**, reproduce it, and export the security log — the class name and
  on-screen text in there are what the check needs.
- **The disguise name is generic.** The App info bounce fires when "System Services" appears on a
  Settings page as it opens. If some unrelated page happens to contain that text, it could bounce
  you off it too.
- **Not tamper-proof.** A determined attacker with `adb`, recovery, or safe mode can still remove
  the app. This raises the bar against an opportunistic thief, not a forensic one.
