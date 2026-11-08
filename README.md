# Phone Blocker

Personal Android blocker for apps and websites (incognito included), with request-and-wait locks so impulse-you can't just flip it off or uninstall it.

## How it works

- App blocking: `AppBlockerService` (AccessibilityService) watches window changes. A blocked package in the foreground gets a Home press plus a short "Blocked" screen.
- Website blocking: `DnsVpnService` is a local VPN that only routes a fake DNS server into the tunnel. Blocked domains (and subdomains) get NXDOMAIN, everything else is forwarded to 1.1.1.1 / 9.9.9.9. Works in incognito and in any browser, since it filters at DNS.
- Lock: `LockManager`. Tightening changes are instant (protection on, adding to lists, longer delay, shorter unlock window). Loosening changes are queued requests that wait out the request delay, then must be confirmed within an hour. Each request type has its own slot: turn protection off, remove list items, change timing, unlock uninstall.
- Uninstall lock: while the accessibility service runs, the guard bounces you home from Settings screens, the system uninstall dialog and the VPN manage dialog whenever they show "Phone Blocker" as a title or list row (the Settings home page and long app lists like Apps are exempt, but tapping into the app's own page is not). That covers App info, the accessibility service page, VPN settings, device admin and Settings search. "Request uninstall unlock" queues a request; once confirmed, the guard opens for the unlock window, then locks again. "Lock again now" ends it early.
- Timing: request delay (default 1 minute, for testing) and unlock window (default 10 minutes), both adjustable under Timing. Each request remembers the delay it was made under, so changing the setting can't shorten a request already waiting.
- Device admin makes uninstalling a two-step chore. Boot receiver and the accessibility watchdog restart the VPN if it dies.

Defaults: Reddit and Discord (apps and domains). Pick apps from a searchable list of installed apps ("Choose apps"); edit blocked sites as plain text. Removals from either list go through the delay.

## Build and install

1. Open the folder in Android Studio and let Gradle sync (needs JDK 17 and Android SDK 34).
2. Run on a phone, or Build > Build APK(s) and sideload.
3. In the app: tap Enable protection and accept the VPN prompt, grant Accessibility, activate Device admin.

CLI builds need the wrapper jar: run `gradle wrapper --gradle-version 8.7` once.

## Uninstalling on purpose

1. Request uninstall unlock, wait out the delay, tap Confirm.
2. Within the unlock window: Settings > Security > Device admin apps > deactivate Phone Blocker (uninstall is greyed out while it's active).
3. Uninstall as normal. Android Studio / `adb uninstall` also works and is not guarded.

## Settings that matter

- Private DNS: set to Off or Automatic. A strict Private DNS hostname makes Android try DNS-over-TLS to the fake DNS address and all DNS breaks.
- Chrome: leave "Use secure DNS" off or on Automatic. The common DoH provider hostnames are blocked as a backstop.

## Known gaps

- Mobile data and other VPN apps: Android allows only one active VPN, so starting another VPN kicks this one off (the watchdog can't restart it without your consent).
- Clock: delays use wall-clock time, so changing the system time shortcuts the locks.
- The uninstall guard is text matching on the screen, so it depends on OEM screens and language. It looks for the app name "Phone Blocker" on Settings, installer and VPN-dialog packages. Test it on the real phone. It also means Settings pages that mention the app (including the whole Accessibility list) are off limits while locked.
- Not covered: safe mode, factory reset, `adb uninstall`, force-stopping from a screen the guard misses. Turn USB debugging off when you're done developing.
- Disabling the VPN from a notification shade on a skin the guard doesn't recognize. Always-on VPN with "Block connections without VPN" (system VPN settings) closes that hole at the cost of losing internet when the filter is off.
- Apps using hardcoded IPs or their own DoH endpoints bypass DNS filtering.
- Only IPv4 UDP DNS is handled.

## Next steps

- Block windows (schedules) instead of always-on.
- Clock-tamper resistance using elapsed-realtime plus boot count.
