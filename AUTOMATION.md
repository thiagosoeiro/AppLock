# Automation control

This fork exposes AppLock's global protection flag to automation apps, so that protection can
follow a condition you choose — a trusted Wi-Fi network, a time of day, a location — instead of
being switched by hand.

It adds no conditions of its own. AppLock only accepts "protection on" and "protection off";
MacroDroid, Tasker or `adb` decides when to send them.

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
