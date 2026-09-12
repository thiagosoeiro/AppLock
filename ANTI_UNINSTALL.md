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

Turn on **Settings → Anti-Uninstall Protection**. It needs two permissions — Device Administrator,
including "Lock the screen", and the Accessibility Service — and with both granted the app resists
removal.

> **Updating from a version without "Lock the screen"?** The main screen asks for device admin
> again. Tap it and activate: Android adds the new permission to the admin you already granted,
> without turning it off. Until you do, the locks that go through device admin can't work.

With it **on**, these are what each Settings surface does:

| On the phone, go to | What happens |
|---|---|
| Settings → Accessibility → System Services | Bounced off the page and the screen locks |
| Turning that service off anyway | The screen locks |
| Settings → Apps → System Services | Bounced off the page and the screen locks |
| Settings → Apps → System Services → Uninstall | Opens the device admin page for System Services |
| Device admin apps → System Services (path varies by phone) | Bounced off and the screen locks, with the toast "This action isn't allowed." |
| Tapping Deactivate there anyway | The screen locks on the tap; removing the admin still needs OK on a second prompt |
| Tapping "Deactivate and uninstall" anyway | The admin is removed and the screen locks before the uninstall dialog appears |
| The uninstall dialog for System Services | Bounced off and the screen locks |
| Ordinary pages (Wi-Fi, Display, …) | Nothing happens; they work normally |

> **It blocks you, too.** These pages bounce whoever opens them, including you. To change any of
> these settings yourself, first turn Anti-Uninstall Protection **off** — that asks for your PIN —
> and then open the page.

### How each button is covered

- **Uninstall** — while device admin is active, Android sends Uninstall to the device admin page,
  where "Deactivate and uninstall" removes the admin and opens the uninstall dialog in one tap. That
  page bounces you, and if a tap gets through anyway, the screen locks before the dialog appears.
- **Force stop** — greyed out by Android for any app with an active device admin. It becomes
  tappable only once the admin is gone, and removing the admin locks the screen.
- **Clear data / Force stop / anything else on App info** — the accessibility service bounces you
  off the app's own App info page before you can reach them.

### Locks that don't wait for the page

The bounces come from the accessibility service reading each page as it opens, so a fast enough tap
can still beat them. Three moments don't rely on that: Android tells the app directly, and the app
locks the screen.

- **Deactivate is tapped.** Android asks the app before removing anything. The app locks the screen
  and hands back a warning, so removal needs OK on a second prompt, now behind the lock screen.
- **Device admin is removed**, by Deactivate or by "Deactivate and uninstall". The app locks the
  screen; for "Deactivate and uninstall" that happens before Android stops the app and opens the
  uninstall dialog. Anti-uninstall stays on, so the other guards keep working and the main screen
  asks for device admin back.
- **The accessibility service is turned off.** The app locks the screen through device admin.

Someone who doesn't know the phone's own PIN is stopped at any of these locks. If you know it you
can unlock and carry on, so when you test this yourself, check that the screen locks, not that you
can't get past it.

### Limitations

- **OEM variance.** Settings pages are named differently on each skin. The detection is tuned
  against stock Android and One UI (tested on a Galaxy S24 Ultra, One UI 8.5); another skin may
  title a page differently and slip past. If a page isn't caught on your phone, enable
  **Settings → Advanced → Logging**, reproduce it, and export the security log — the class name and
  on-screen text in there are what the check needs.
- **The disguise name is generic.** The App info bounce fires when "System Services" appears on a
  Settings page as it opens. If some unrelated page happens to contain that text, it could bounce
  you off it too.
- **Bounces can still lose to a very fast tap.** Each page is checked as it opens and for about a
  second while it finishes drawing. The locks above cover a tap that gets through first.
- **The uninstall dialog check depends on the installer's screen names.** It only fires within a few
  seconds of the package installer opening an uninstall screen, so installing an update isn't
  blocked. If a phone's installer names its screens differently it doesn't fire; the security log
  records the names it saw.
- **Not tamper-proof.** A determined attacker with `adb`, recovery, or safe mode can still remove
  the app. This raises the bar against an opportunistic thief, not a forensic one.
