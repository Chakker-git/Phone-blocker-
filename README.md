# Phone Blocker

Personal Android blocker for apps and websites (incognito included), with a delayed-disable lock so impulse-you can't just flip it off.

## How it works

- App blocking: `AppBlockerService` (AccessibilityService) watches window changes. A blocked package in the foreground gets a Home press plus a short "Blocked" screen.
- Website blocking: `DnsVpnService` is a local VPN that only routes a fake DNS server into the tunnel. Blocked domains (and subdomains) get NXDOMAIN, everything else is forwarded to 1.1.1.1 / 9.9.9.9. Works in incognito and in any browser, since it filters at DNS.
- Lock: `LockManager`. Turning protection on or adding to the lists is instant. Turning it off or removing anything is queued, waits `DELAY_MS` (currently 60 seconds, for testing), then must be confirmed within 10 minutes.
- Device admin makes uninstalling a two-step chore. Boot receiver and the accessibility watchdog restart the VPN if it dies.

Defaults: Reddit and Discord (apps and domains). Edit in the app.

## Build and install

1. Open the folder in Android Studio and let Gradle sync (needs JDK 17 and Android SDK 34).
2. Run on a phone, or Build > Build APK(s) and sideload.
3. In the app: tap Enable protection and accept the VPN prompt, grant Accessibility, activate Device admin.

CLI builds need the wrapper jar: run `gradle wrapper --gradle-version 8.7` once.

## Settings that matter

- Private DNS: set to Off or Automatic. A strict Private DNS hostname makes Android try DNS-over-TLS to the fake DNS address and all DNS breaks.
- Chrome: leave "Use secure DNS" off or on Automatic. The common DoH provider hostnames are blocked as a backstop.

## Known gaps

- Mobile data and other VPN apps: Android allows only one active VPN, so starting another VPN kicks this one off (the watchdog can't restart it without your consent).
- Clock: delays use wall-clock time, so changing the system time shortcuts the lock.
- Disabling the VPN in system settings, force-stopping the app or turning off the accessibility service are not delayed. Always-on VPN with "Block connections without VPN" (system VPN settings) closes the VPN hole at the cost of losing internet when the filter is off.
- Apps using hardcoded IPs or their own DoH endpoints bypass DNS filtering.
- Only IPv4 UDP DNS is handled.

## Next steps

- Block windows (schedules) instead of always-on.
- Raise `LockManager.DELAY_MS` and move it into config.
- Have the accessibility service guard this app's own Settings / uninstall screens.
