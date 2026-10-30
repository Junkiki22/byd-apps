# DiLink ACC cycle vs. always-on VPN (Tailscale)

Verified on the Dolphin (DiLink 3 / Android 10) on 2026-10-30 from logcat.

## Finding

- **ACC_OFF** (`QtiDCT-C: ACC_OFF`): ActivityManager force-stops user apps
  (`am_kill ... stop com.tailscale.ipn`, `com.wheregoes.carha`, `com.bydmate.app`). The VPN
  network goes `DISCONNECTED`.
- **ACC_ON**: not a reboot (uptime keeps counting) — the system resumes and re-broadcasts
  `BOOT_COMPLETED`. Apps with a boot receiver come back (car-ha via `BootReceiver`, Tailscale via
  WorkManager's `RescheduleReceiver`), but **always-on VPN does not reconnect**, even with
  `always_on_vpn_app=com.tailscale.ipn` and `ACTIVATE_VPN: allow`.
- Observed: ACC_ON 13:07:12, Tailscale process up 13:07:22, VPN only up at 13:08:27 — 9 s after the
  Tailscale UI was opened by hand. car-ha's webhook (over the tailnet) had no route until then.

## Workaround (car-ha 1.1.3)

Tailscale exports `com.tailscale.ipn/.IPNReceiver` with actions `com.tailscale.ipn.CONNECT_VPN` /
`DISCONNECT_VPN`. car-ha sends `CONNECT_VPN` (explicit component, `FLAG_INCLUDE_STOPPED_PACKAGES`)
from `BootReceiver` and from `BridgeService.onCreate`, plus retries at +15 s and +45 s. Sending it
while connected is a no-op.

Test: `DISCONNECT_VPN` via `am broadcast` → 10 s → `CONNECT_VPN` → tun up 0.8 s later.
