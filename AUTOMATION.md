# Automation control

This fork exposes AppLock's global protection flag to automation apps, so that protection can
follow a condition you choose — a trusted Wi-Fi network, a time of day, a location — instead of
being switched by hand.

The intents add no conditions of their own. AppLock only accepts "protection on" and "protection
off"; MacroDroid, Tasker or `adb` decides when to send them. For the most common condition, a
trusted Wi-Fi network, there is also a [built-in option](#built-in-trusted-wi-fi) that needs no
automation app.

## Setup

1. Open **Settings → Automation** and turn on **Allow automation apps**.
2. Tap **Access token**, then **Copy token**.

The receiver stays disabled at the package level until that switch is on, so with the feature off
there is nothing exported to reach.

## Intents

| Action | Effect |
| --- | --- |
| `dev.pranav.applock.action.ENABLE_PROTECTION` | Protection on, and any app unlocked while it was off is re-locked |
| `dev.pranav.applock.action.DISABLE_PROTECTION` | Protection off |
| `dev.pranav.applock.action.QUERY_PROTECTION_STATE` | No change; replies with the current state |

Every action requires the string extra `token`, matching the token from Settings. Requests with a
wrong, missing or stale token are ignored, as is anything sent while automation access is off.

These are **setters, not a toggle**, on purpose. An automation app cannot read AppLock's state, so
a toggle would force it to track state of its own — and that tracked state drifts out of sync the
first time anything is missed, leaving protection off when you believe it is on. `ENABLE` and
`DISABLE` are idempotent: sending either twice is harmless, and no state can desync.

Broadcasts must target the package explicitly. Android does not deliver implicit broadcasts to
manifest-declared receivers, so a broadcast with only an action set will silently do nothing.

## Reading the state back

Whenever protection changes — by intent or by the shield toggle in the app — AppLock broadcasts:

```
dev.pranav.applock.action.PROTECTION_STATE   boolean extra "state"
```

This one is implicit, so a dynamically registered receiver picks it up. In MacroDroid that is the
**Intent Received** trigger. Use it to verify rather than assume.

Ordered senders can also read the result code directly: `0` rejected, `1` protection off,
`2` protection on.

## MacroDroid

Two macros, no variables, nothing to keep in sync:

- **Trigger** Wi-Fi → Connected to *your SSID* → **Action** Send Intent
- **Trigger** Wi-Fi → Disconnected from *your SSID* → **Action** Send Intent

Send Intent settings:

| Field | Value |
| --- | --- |
| Target | Broadcast |
| Action | `dev.pranav.applock.action.DISABLE_PROTECTION` (or `ENABLE_…`) |
| Package | `dev.pranav.applock` |
| Class | `dev.pranav.applock.core.broadcast.AutomationReceiver` |
| Extra | name `token`, type String, value *your token* |

Fail closed: put the `ENABLE` macro on *disconnect*, not on "connected to any other network", so
that losing Wi-Fi entirely still re-enables protection.

## Testing with adb

```sh
TOKEN=<token from Settings>

adb shell am broadcast \
  -a dev.pranav.applock.action.DISABLE_PROTECTION \
  -n dev.pranav.applock/.core.broadcast.AutomationReceiver \
  --es token "$TOKEN"

adb shell am broadcast \
  -a dev.pranav.applock.action.QUERY_PROTECTION_STATE \
  -n dev.pranav.applock/.core.broadcast.AutomationReceiver \
  --es token "$TOKEN"
```

`am broadcast` sends an ordered broadcast, so it prints the result code and data.
Rejected requests return `result=0`; enable **Settings → Advanced → Logging** to see the reason in
the audit log.

## Security notes

Anything that can switch protection off is worth being careful about. Three things guard it:

- the receiver component is disabled until you opt in, so by default it is not part of the app's
  exported surface at all;
- every action requires the token, compared in constant time;
- **Replace** in the token dialog invalidates the old token immediately.

Anyone who can already run `adb` against an unlocked device could read the token out of the app's
private storage — the token protects against other *apps* on the device, not against someone
holding an unlocked phone with USB debugging on.

## Built-in trusted Wi-Fi

For the most common condition no automation app is needed. **Settings → Trusted Wi-Fi** lets locked
apps open without authentication while the phone is connected to a network you trust, and keeps
them locked everywhere else.

### Setup

1. Turn on **Open locked apps on trusted Wi-Fi**. Grant precise location, then choose **Allow all
   the time**, and keep Location switched on.
2. Connect to the network, open **Trusted networks**, and tap **Add**.

Android only shares the name of the connected Wi-Fi network with apps that have location access,
and only in the background if that access is "Allow all the time". The app reads the network name
and nothing else; it never asks for your position.

The shield on the main screen shows **HOME** while a trusted network is keeping apps open.

### Fails closed

Anything the app can't confirm counts as untrusted, so apps stay locked:

- Trust is kept in memory, never stored. After a reboot or restart, apps stay locked until Android
  reports a trusted network.
- Wi-Fi off, no connection, an untrusted network, Location off, or location access missing or set
  to "While using the app": locked.
- Leaving a trusted network re-locks everything, the same as turning the shield back on.
- Each time the screen turns on or the phone unlocks, the app reads the network name again. If it
  can't, or the network isn't trusted, apps lock at once. This check never unlocks anything.

Anti-uninstall is not affected by any of this.

### With the intents

Both can be used together. The intents turn the shield on and off. Trusted Wi-Fi never changes the
shield; it only keeps apps open while the shield is on. With the shield off, apps open everywhere,
trusted network or not. `QUERY_PROTECTION_STATE` and `PROTECTION_STATE` report the shield only.

### Limits

- Networks are matched by name. Someone holding your unlocked phone could name a hotspot the same,
  connect the phone to it, and open locked apps.
- As you walk away, Wi-Fi stays connected until the signal drops, and locked apps keep opening
  until then.
