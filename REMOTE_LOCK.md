# Remote lock

Send a keyword to this phone from another phone, by SMS or in a chat app, and it switches protection
on, re-locks every app and, if you choose, locks the phone. It is meant for a phone that is lost or
lent to someone. This is item 14 of [FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md).

It only ever locks. The keyword stays readable in the chat, so a keyword that unlocked anything
would hand the phone to whoever reads it. There is no remote unlock.

## What a message does

A message whose whole text is the keyword:

1. switches protection on, the same as the shield or the automation intents. That re-locks every
   app, including one open at that moment, as soon as its screen next changes;
2. locks the phone, if **Also lock the phone** is on (it is by default);
3. emails you a short report, if **Email me when it locks** is on and the intruder alert email is
   set up.

Case, extra spaces and punctuation at either end don't matter, so "Are you home yet?" matches "are
you home yet". The keyword inside a longer message does nothing.

Nothing shows outside the PIN: no notification and no toast. Android's permission pages name the
app "System Services", like everywhere else outside the PIN; see
[ANTI_UNINSTALL.md](ANTI_UNINSTALL.md).

## Setting it up

1. **Settings → Remote lock → Keyword.** Pick something that reads like an ordinary message, such
   as a question, since it stays in the chat and can show in a notification. It needs at least 8
   characters, including a letter: Android 15 and later hides text that looks like a one-time code
   from apps like this one.
2. Allow one or both ways in, from the rows under the keyword:
   - **Notification access** sees the message in any app's notification: SMS, RCS chats, WhatsApp,
     Telegram and more.
   - **SMS** reads plain SMS directly, even when the messaging app is muted.
3. Turn on **Lock by message**.

To test it, send the keyword from another phone, then unlock and open a locked app: it should ask
for your PIN. Without a second phone, an automation app that can show a notification with your own
text tests the notification route.

### Which to allow

| The message arrives as | Notification access | SMS |
| --- | --- | --- |
| Plain SMS | Yes, if the messaging app shows it | Yes |
| RCS chat (chat features in Google or Samsung Messages) | Yes | No |
| WhatsApp, Telegram, Signal and similar | Yes | No |
| A muted chat, or an app set to hide message text | No | Plain SMS only |

RCS never reaches an SMS receiver, and between two Android phones a "text" is often RCS. Allow both
for the widest cover; an SMS seen both ways still locks once.

### If Android greys out SMS

On Android 15 and later, an app installed from outside the Play Store can't be allowed SMS until you
allow restricted settings for it. Anti-uninstall bounces you off App info, so:

1. Turn **Anti-Uninstall Protection** off. It asks for your PIN.
2. Open **Settings → Apps → System Services**, tap **⋮**, then **Allow restricted settings**.
3. Back in this app, tap **SMS** and allow it.
4. Turn anti-uninstall back on.

Notification access is restricted the same way. If its page bounces you while anti-uninstall is on,
turn anti-uninstall off while you allow it.

## Which messages count

A message counts once, and only if it was sent after **Lock by message** was turned on and after the
keyword was last saved. So a test message, or a keyword already sitting in a chat, can't lock the
phone later. A message held up by airplane mode or a switched-off phone still acts when it arrives.

Within a minute of a remote lock, another keyword message is ignored: one SMS usually arrives both
as an SMS and as a notification.

## The report email

It goes out through the same Resend settings as [intruder alerts](INTRUDER_ALERTS.md), with the same
neutral "System report" subject, and waits on the phone the same way when there is no network. It
says:

- when the message arrived, in local time and UTC;
- whether it came as an SMS, or in which app's notification;
- whether protection was already on;
- whether the phone locked, and what that needs if it couldn't;
- whether the phone is on trusted Wi-Fi (see below);
- where the phone is, if **Include location** is on under Intruder alerts.

At most one report goes out every 10 minutes, so someone repeating the keyword can't use up Resend's
daily allowance or push waiting intruder alerts out. The phone still locks every time.

## Trusted Wi-Fi

A remote lock doesn't override [trusted Wi-Fi](AUTOMATION.md#built-in-trusted-wi-fi). On a trusted
network the shield comes on and the phone locks, but once the phone is unlocked, locked apps open
while it stays connected. The report says when that is the case.

## Limits

- **No connection, no message.** A phone with no signal, in airplane mode or switched off receives
  nothing until it's back; then the message acts.
- **Notifications have to show the message.** A muted chat, or an app set to hide message text in
  its notifications, isn't seen; plain SMS still is, if SMS is allowed.
- **Anyone who reads the keyword can lock the phone.** Only lock: it can't unlock anything. Change
  it if you think someone has seen it.
- **Anti-uninstall doesn't guard these permissions.** Someone who turns off SMS or notification
  access for System Services closes that way in.
- **Locking the phone needs a way to lock it:** the accessibility service, or device admin with
  "Lock the screen". Without either, apps re-lock but the screen doesn't.
- **After a reboot nothing runs until the phone is first unlocked,** as Android holds the app until
  then. The phone is locked in the meantime anyway.
