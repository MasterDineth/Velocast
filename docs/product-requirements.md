# Product requirements

## Decisions

| ID | Decision | Detail | Reason |
| --- | --- | --- | --- |
| D1 | Launcher priority | ADB first (Wi-Fi, then USB OTG), Shizuku second, root optional. | ADB needs nothing on the old phone beyond debugging switches; Shizuku adds hands-free restart after reboot; root is the smallest audience and the highest risk. |
| D2 | Rescue-first product | Optimise for old phones whose screen or touch is broken. After a one-time Prepare step the old phone needs zero input. | A broken screen means nobody can tap a consent dialog, toggle a setting or read a pairing code later. |
| D3 | Trust model | Trust is established once, during Prepare, through Android's own ADB authorisation. There is no per-session consent prompt on the old phone. | Replaces the on-target consent recommended in the audit report (Section 5.3), which cannot work when the display is unusable. |
| D4 | Two apps, both redesigned | Viewer (new phone) and Host (old phone: Prepare, keep-alive, status). Host is optional for pure ADB use but strongly recommended for rescue readiness. | Preparation, keep-alive and reboot recovery need an app on the old phone; the mirroring itself does not. |
| D5 | Language and UI toolkit | Kotlin with Jetpack Compose and Material 3 for all new code. Existing Kotlin code stays Kotlin. | Compose is Kotlin-only and the repository is already Kotlin. If you prefer Java, see the note in Section 2.5. |
| D6 | Server delivery | server-core is built as a dex jar. For ADB the viewer pushes and starts it; for Shizuku and root the Host app starts it. | One code base for every launcher; nothing to install on the old phone for the ADB route. |
| D7 | Session security | Authenticated, encrypted streams (AEAD). Per-session secret for ADB launches; per-pairing secret for Host-started servers. Secrets live in Keystore-protected storage. | Closes SEC-01 and SEC-02 without relying on the LAN being trusted. |

## Rescue scenarios

| Situation | Outcome | What to do |
| --- | --- | --- |
| Touch dead, display works | Works | Mirror and control through injected input. Needs USB debugging enabled and the viewer authorised. If not authorised: OTG mouse route (Rescue guide). |
| Display dead or black, touch works | Works | The phone still composes the display, so capture works. Needs the same prerequisites. Wake is sent as a key event, not a power toggle. |
| Both display and touch unusable | Works if prepared | Fully depends on Prepare having been done while the phone still worked. |
| Ghost or random touches | Partly | Injected input still works, but the broken digitiser keeps adding its own touches. Use a screen protector off, power-cycle, or accept noise; the app cannot filter hardware input. |
| Phone rebooted since breaking | Needs recovery | Wireless debugging is off after a reboot. Reconnect over USB OTG, or rely on Host app, Shizuku or root auto-restart (needs Wi-Fi, and may wait for the first unlock). |
| Secure lock screen after reboot | Limited | Apps may not run until the first unlock. Options: an OTG keyboard typing the PIN blindly, the opt-in Unlock helper once ADB is reachable, or removing the lock during Prepare (a security trade-off the owner must choose). |
| Debugging never enabled, display visible | Rescue guide | Use an OTG mouse to enable USB debugging and authorise the viewer once, then connect. |
| Debugging never enabled, display dead | Very limited | External-display adapter, OTG keyboard with TalkBack, or a recovery service. State this honestly in the app. |
| Does not boot | Out of scope | Repair first. |

## Platform limits

| ID | Fact | Consequence | Basis |
| --- | --- | --- | --- |
| L1 | ADB authorisations expire after 7 days without a reconnect unless the developer option 'Disable adb authorization timeout' is on (introduced in Android 10). | Prepare must turn it off, or the viewer must reconnect at least weekly. | Documented in AOSP change notes and developer guides |
| L2 | Wireless debugging works only on Wi-Fi, is switched off by a reboot (pairing is kept) and can be switched off when the phone changes network. | Treat reboot as the main enemy; offer USB OTG and boot recovery. | Shizuku documentation and community guides |
| L3 | USB debugging itself stays enabled across reboots. | USB OTG is the dependable transport for a rebooted phone. | General Android behaviour |
| L4 | An app can turn wireless debugging on only with WRITE_SECURE_SETTINGS, which can be granted once over ADB (`pm grant`). | The viewer grants it to the Host app during Prepare; the Host boot receiver then re-enables wireless debugging once Wi-Fi is up. | Shizuku community fork documentation |
| L5 | Shizuku on a non-rooted phone must be restarted after each reboot; community forks add start-on-boot that waits for Wi-Fi (and may wait for unlock). | Optional launcher; never the only path. | Shizuku community wiki |
| L6 | The classic TCP debugging port (adb tcpip) is cleared on reboot and is less secure than paired wireless debugging. | Do not use it by default. | Shizuku community wiki |
| L7 | USB debugging must already be enabled, and the viewer authorised, before the screen breaks; it cannot be enabled afterwards without a working display or an OTG mouse. | Prepare is a first-class flow; the Rescue guide covers late cases. | scrcpy and recovery guides |
| L8 | Some ROMs require an extra developer toggle before the shell user may inject input (for example Xiaomi 'USB debugging (Security settings)'). | Detect and show a specific error and fix (INJECTION_DENIED). | scrcpy release notes added a dedicated error for this case |
| L9 | Protected (FLAG_SECURE) windows may capture as black. | Document; no workaround expected for shell-level mirroring. | Platform behaviour |
| L10 | After a reboot with a secure lock, boot-time work may be deferred until first unlock (Direct Boot). | Confirm on devices; design the boot receiver for both locked-boot and boot-completed events. | Needs device verification |

## Requirements

| ID | Requirement | Detail |
| --- | --- | --- |
| RQ-01 | One-tap connect | From a cold start of the viewer, a paired phone connects with one tap (or automatically when auto-connect is on), target 10 s or less on the same Wi-Fi. |
| RQ-02 | Zero input on the old phone | After Prepare, no touch, dialog or confirmation is needed on the old phone for any session. |
| RQ-03 | Automatic wake | Connecting wakes a sleeping display with KEYCODE_WAKEUP (never a power toggle) and keeps it awake while streaming. |
| RQ-04 | Lock screen | Optional, opt-in Unlock helper enters a stored PIN securely; otherwise the viewer shows the lock screen and allows typing. |
| RQ-05 | Reboot recovery | Where the platform allows, Host app, Shizuku or root re-enable wireless debugging and the server after boot; otherwise the viewer states exactly what to do (USB cable). |
| RQ-06 | Auto-reconnect | After a link loss the viewer retries with back-off for at least 10 minutes with a visible state and no manual restart. |
| RQ-07 | Keep-alive | The old phone stays awake and connected for 8 hours or more while charging (stay-awake, Wi-Fi lock, battery-optimisation exemption). |
| RQ-08 | Text input | The viewer can type text into the old phone, including non-ASCII through clipboard paste. |
| RQ-09 | Display behaviour | The user can switch the old panel off while mirroring; the previous state is restored when the session ends. |
| RQ-10 | Prepare wizard | While the old phone still works, the Host app verifies and fixes every prerequisite in under five minutes, with the viewer automating what it can over ADB. |
| RQ-11 | Rescue guide | An illustrated guide for the OTG-mouse route and an honest statement of what is not possible. |
| RQ-12 | Multiple phones | A device list shows per-phone status (Ready, Needs attention, Offline). |
| RQ-13 | Security | No unauthenticated access; encrypted sessions; paired viewers can be revoked; secrets in Keystore-protected storage. |
| RQ-14 | Privacy | No telemetry by default; diagnostics are shared only by explicit user action. |
| RQ-15 | Accessibility | TalkBack labels, 48 dp targets, AA contrast, 200% font scale, no colour-only status. |
| RQ-16 | Performance | Meet the latency and recovery targets of the audit report (median under 80 ms, p95 under 150 ms at 1080p60 on clean 5 GHz; artefact recovery under 500 ms). |
| RQ-17 | Optional hands-free boot | Shizuku and root launchers restart the server after boot without anyone touching the phone. |

## Error catalogue

| Code | Message | Primary fix |
| --- | --- | --- |
| DEBUGGING_OFF | USB or Wireless debugging is off on the old phone. | Connect the USB cable; open the Rescue guide. |
| WIRELESS_OFF_AFTER_REBOOT | The old phone restarted, so wireless debugging switched off. | Connect with a USB cable, or enable automatic restart in the Host app. |
| DIFFERENT_NETWORK | The two phones are on different Wi-Fi networks. | Join the same network, then retry. |
| AUTH_EXPIRED | This viewer's authorisation expired (7-day rule). | Re-authorise using the OTG mouse guide; turn off the timeout in Prepare next time. |
| NOT_PAIRED | This viewer is not paired with that phone. | Start Add old phone. |
| SERVER_START_FAILED | The mirror service could not start. | Show the cause line and a Copy diagnostics button. |
| INJECTION_DENIED | This phone blocks touch control from debugging. | Show the ROM-specific toggle to enable, with a link to the guide. |
| SCREEN_OFF | The old phone's screen is off. | Wake it (one tap). |
| LOCKED | The old phone is locked. | Unlock helper, or type the PIN with the keyboard. |
| VERSION_MISMATCH | The viewer and the mirror service are different versions. | Update the older app; the viewer re-pushes the service automatically. |
| LINK_LOST | The connection was lost. | Reconnecting automatically; show Retry now and Give up. |

## Rescue acceptance scenarios

| ID | Setup | Action | Expected |
| --- | --- | --- | --- |
| R1 | Prepared phone, screen off, locked with PIN, Unlock helper on | Tap Connect once | Phone wakes, unlocks once, mirror appears in 10 s or less; no on-device interaction |
| R2 | Prepared phone, Wi-Fi switched off for 5 s | Stay in the viewer | Reconnecting banner, automatic recovery within 3 s of Wi-Fi returning |
| R3 | Prepared phone rebooted, Host app with secure settings granted, no screen lock | Wait for boot, then Connect | Wireless debugging returns by itself; mirror starts without touching the phone |
| R4 | Prepared phone rebooted, secure lock enabled | Connect before and after first unlock | Behaviour recorded; viewer message states exactly what is needed (USB cable or unlock) |
| R5 | Prepared phone, wireless debugging off | Connect with a USB OTG cable | USB session works; data-channel mode shows Tunnel |
| R6 | Unprepared phone, display visible, touch dead | Follow the Rescue guide with an OTG mouse | USB debugging enabled and viewer authorised; mirror works |
| R7 | Old phone display physically dead, prepared | Connect and type a message | Mirror shows content; typing and paste work |
| R8 | Authorisation older than 7 days with timeout disabled in Prepare | Connect | Connects without a prompt |
| R9 | Two old phones in the list | Auto-connect on launch | Chooser appears; chosen phone connects; status chips correct |
| R10 | Hostile device on the LAN | Connect to the server port without credentials | No data sent; active session unaffected; failure logged |
