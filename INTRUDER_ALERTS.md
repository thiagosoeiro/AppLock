# Intruder alerts

After a run of wrong PINs, patterns or passwords, this fork can take a front-camera photo
or a short video, add the phone's location, and email it to you. It is items 8 and 13 of
[FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md) built together: a capture kept only on the
phone leaves with a thief, so the alert is emailed and then deleted from the phone. There is
no gallery of captures on the device.

The feature is off until you turn it on and enter your own email details. Nothing about it
is visible outside the PIN: there is no notification, and the camera and microphone are only
ever asked for from Settings, where the app is named "System Services" like everywhere
outside the PIN wall (see [ANTI_UNINSTALL.md](ANTI_UNINSTALL.md)).

## What triggers an alert

Every wrong entry the app already counts feeds this, so it needs no counting of its own: a
locked app's lock screen, the app's own PIN, Change PIN, and the screen that turns
anti-uninstall off all share one running count, which a correct entry or a strong biometric
resets.

You choose after how many wrong tries in a row an alert is sent (2 to 5, default 3). An
alert is sent at that number, and again at each multiple of it, up to three times in one run
— so at the default, alerts go out at 3, 6 and 9 wrong tries, and no more until the count
resets. The first four wrong tries never cost a wait, so a threshold in this range always
fires before the lockout delay begins.

## What is sent

Each alert is one email:

- **Subject:** a neutral "System report", in case the receiving mailbox is also signed in on
  this phone.
- **Body:** the local and UTC time, how many wrong tries in a row, and which app was on the
  lock screen (or "the app's own PIN screen").
- **Location**, if you turned it on: a Google Maps link with its accuracy and how old the fix
  is, or a short reason if there is none. It reuses the location permission trusted Wi-Fi
  already uses and Android's own providers, so it adds no permission.
- **Capture:** a `photo.jpg` or a `video.mp4` attached. Video records with sound only if you
  grant the microphone. If the capture failed — the camera was busy, a permission was
  missing, the screen went off — the email is still sent and says why.

## Setting it up

You need a [Resend](https://resend.com) account and an API key.

1. In Resend, create an API key with **Sending access** only, not full access. A sending key
   can do nothing but send email, so a copy of it can't touch the rest of your account.
2. In the app: **Settings → Intruder alerts → Email (Resend)**, and paste the key, the sender
   and where to send alerts. Turn the switch on; it will ask for the camera, and the
   microphone too for video.
3. Use **Send a test alert** to check the whole thing end to end. It captures and emails one
   alert now and shows the result, or Resend's own error if something is wrong.

### The sender and recipient without a domain

Until you verify a domain in Resend, Resend only lets you send **from `onboarding@resend.dev`**
(the default the app fills in) and **only to your own Resend account's email address**. To
send from your own address, or to any other recipient, verify a domain in Resend first, which
also sets up SPF and DKIM. The free plan allows 100 emails a day, far more than wrong-try
alerts will produce.

### Keep the key off the repo, and mind the mailbox

- The key is typed into the app and stored encrypted under a key in the Android Keystore,
  which never leaves the phone. A backup or a copy of the app's data carries only the
  ciphertext, which reads as "not set" anywhere else. The app never logs it and only ever
  shows its presence, never the value.
- **Never put the key in this repository or in CI.** The fork is public and CI build
  artifacts can be downloaded, so a key committed anywhere here is a key handed out.
- If the mailbox you send to is also signed in on this phone, a thief holding the phone could
  see the alert arrive. The neutral subject helps, but for real safety use a mailbox that
  isn't on the phone (which needs a verified domain), or a rule that files the alert away from
  the inbox.

## When sending can't happen at once

If there is no network, or Resend is rate-limited or briefly down, the alert waits on the
phone and a background job retries it once there is a network again — for example after a
thief turns airplane mode off. Each alert carries an idempotency key, so a retry never sends a
second copy. If Resend refuses the request itself (a bad key, an unverified sender, a
disallowed recipient), the alert is kept and the reason is shown in Settings the next time you
open the email details; it is retried when you save the settings again or when the next alert
is made. At most 10 alerts wait at a time, for up to 7 days.

## Limits

- **The screen going off ends a video.** A clip cut short that way is still sent, as far as it
  got.
- **A camera already in use** by another app means no capture; the email still goes out and
  says so.
- **Airplane mode delays sending,** not capturing — the alert waits and is sent once there is
  a network.
- **After a reboot, nothing runs until the phone is first unlocked** by its owner, as Android
  holds the app until then.
- **Your own mistyped code triggers an alert too.** That is the point — the app can't tell
  your thumb from a stranger's — so expect the occasional photo of yourself.
- **The camera indicator shows while capturing.** On Android 12+ a green dot appears, and some
  phones chime when a video starts, so a watchful person may notice.
