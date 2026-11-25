# Design specification

Jetpack Compose with Material 3. Tokens live in :designsystem; screens never use raw colours.

## Principles

| Principle | In practice |
| --- | --- |
| Status first | Every screen answers one question: can I rescue this phone right now? Show the state in words, colour and an icon, never colour alone. |
| One primary action | Each screen has one obvious primary button. Connect is reachable in one tap from Home. |
| Minimum input | Use discovery, saved devices, auto-connect and pre-filled values. Ask for typing only for the 6-digit pairing code and an optional PIN. |
| Say what to do | Errors name the cause in plain language and offer one fix button, then details on request. |
| Calm and safe | No jargon on the main path (ADB and Shizuku appear only under Advanced). Destructive or sensitive actions (unlock PIN, revoke) are explained and confirmed. |
| Works for stressed users | People opening this app often just lost access to a phone with important data. Keep it reassuring, fast and predictable. |

## Tokens

| Role | Light | Dark |
| --- | --- | --- |
| Primary | #2563A8 | #8DB4E8 |
| Secondary (accent) | #0F766E | #5EC8BC |
| Surface | #F8FAFC | #0F172A |
| On surface | #1E293B | #E2E8F0 |
| Success | #15803D | #6FD08C |
| Warning | #B45309 | #F2B35E |
| Error | #B91C1C | #F28B8B |
| Info | #0E7490 | #7CCBE0 |

Type (Roboto): Headline 24/32 Medium, Title 20/28 Medium, Body 16/24 Regular, Label 12/16 Medium. Shapes: 8, 12, 20, 28 dp. Spacing on a 4 dp grid. Minimum touch target 48 dp. Status is always icon + text + colour.

## Components

| Component | Behaviour |
| --- | --- |
| StatusChip | Ready, Needs attention, Offline, Connecting. Icon + text + colour. Used on cards and headers. |
| DeviceCard | Device name, model, last seen, status chip, one primary button. Overflow menu: Rename, Details, Forget. |
| StepIndicator | Dots or numbered steps for wizards, announced as 'Step 2 of 4'. |
| ChecklistRow | Icon (done, needs action, optional), title, one-line reason, trailing action. Expands to show the fix. |
| ProgressSteps | Vertical list for Connecting: done, current, pending, with a ring showing overall progress. |
| ErrorPanel | Cause line, one fix button, 'Details' expander and 'Copy diagnostics'. Maps typed SessionError values. |
| ToggleRow / ChoiceRow | Settings rows with title, supporting text and a switch or radio group. |
| Hud | Small chip with latency and bitrate; opacity and corner configurable; hidden by default in rescue mode. |
| ToolboxSheet | Back, Home, Recents, Wake, Screen off, Keyboard, Screenshot; large touch targets; remembers position. |
| PinPad | Used for the optional Unlock helper setup; never logs or screenshots the digits (FLAG_SECURE). |
| EmptyState | Illustration, one sentence, one button (for example 'Add your old phone'). |
| DiagnosticsLog | Scrollable, filterable log with a Copy and Share action. |

## Viewer screens

| Screen | Purpose | Elements | States and rules |
| --- | --- | --- | --- |
| Onboarding | Explain the product in three cards: what it does, why to prepare the old phone first, what permissions are needed. | 3 cards, Skip, Get started; request notifications (optional) and local-network access only when needed. | First launch only; can be reopened from Settings. |
| Devices (home) | List old phones with status and a one-tap Connect. | DeviceCard list, Add button, bottom bar (Devices, Guide, Settings). Auto-connect banner when enabled. | Empty: EmptyState with Add old phone. Offline: card shows last seen and fix hint. Loading: skeleton cards. |
| Add old phone | Pair a new device in one of three ways and finish with an automatic prepare check. | StepIndicator; options Show QR code, Enter pairing code, USB cable (OTG); link to Rescue guide. | Errors from pairing map to ErrorPanel (wrong code, different network, debugging off). |
| Prepare check | Run idempotent shell commands over ADB to set up what can be automated, and report what still needs the old phone. | ChecklistRow list with live results; button to open Host app instructions for items that need the old screen. | Partial success is normal; unresolved items stay visible on the device card. |
| Device details | Show status, method (Wi-Fi, USB), last session, authorisation age, battery, Host app version. | Connect, Wake, Edit name, Unlock helper, Forget device. | Offline: show what to try next. |
| Connecting | Show progress and the likely reason when it stalls. | Ring with N of 4, ProgressSteps (Found, Authenticated, Starting mirror, Waking screen), Cancel. | Timeout after 20 s: ErrorPanel with the specific cause. |
| Mirror viewer | Full-screen mirror of the old phone with touch and keyboard. | Letterboxed SurfaceView, optional HUD, floating Toolbox, keyboard and paste sheet, immersive mode. | Reconnecting overlay with countdown; Paused when in background; Screen off and Locked banners with a fix button. |
| Rescue guide | Help people whose phone is already broken and not prepared. | Decision flow (see figure), illustrated OTG-mouse walk-through, honest limits. | Reachable from Home, from every error and from Add old phone. |
| Settings | Control behaviour with sensible defaults. | Auto-connect, Keep old phone awake, Quality (Auto, Balanced, Max), Unlock helper, Diagnostics, Paired phones, About and licences. | Advanced section hides method names (ADB, Shizuku, Root) from beginners. |
| Diagnostics | Help users and developers understand failures. | Session timeline, last error, capability report, latency stats, Copy and Share. | Redacts secrets and IP addresses on request. |

Navigation: Onboarding -> Devices (home). Devices -> Device details -> Connecting -> Mirror viewer (toolbox sheet, keyboard sheet). Devices -> Add old phone -> Pair (QR, code, USB) -> Prepare check -> Device details. Devices -> Rescue guide -> OTG walk-through -> Pair. Bottom bar: Devices, Guide, Settings. Settings -> Auto-connect, Quality, Unlock helper, Diagnostics, Paired phones.

## Host screens

| Screen | Purpose | Elements | States and rules |
| --- | --- | --- | --- |
| First run | Explain that this app prepares the old phone and keeps it reachable. | Three short cards and a Start button; warns that Prepare should be done while the screen still works. | Skippable after first run. |
| Prepare checklist (hub) | Verify and fix every prerequisite for rescue. | Progress bar, ChecklistRow list: developer options, USB debugging, Wireless debugging, keep authorisation (no 7-day expiry), stay awake while charging, battery optimisation off, start after reboot, optional Shizuku or root; one Fix next item button. | Items that need system screens open the right Settings page; items needing WRITE_SECURE_SETTINGS are fixed automatically once the viewer has granted it. |
| Pairing helper | Guide through Wireless debugging pairing. | Numbered steps, Open Wireless debugging button, hint that the viewer shows the QR code or takes the 6-digit code. | Detects when pairing completes and advances. |
| Status (home) | Show that the phone is ready for rescue. | Ready card, address, method, last session, battery and charging state, toggles for Start after reboot and Keep awake while charging. | Needs-attention state lists the regressed item with a Fix button; notification when something regresses. |
| Advanced | Choose how the server starts and manage trust. | Launch method (ADB, Shizuku, Root), paired viewers with Revoke, logs and diagnostics, Reset. | Revoke and Reset are confirmed with plain-language warnings. |

Navigation: First run -> Prepare checklist (hub) -> Status (home). The checklist opens Pairing helper, Keep-awake and battery, Start after reboot, Launch method (ADB, Shizuku, Root). Status -> Advanced -> Paired viewers, Logs, Reset.

Pairing realism: Android shows the 6-digit pairing code in Developer options; the viewer shows a QR code or accepts the typed code and port. The Host pairing helper only guides and opens Settings.

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

## Copy rules

- Use the words 'old phone' and 'viewer'; avoid 'host', 'client', 'daemon' and 'ADB' on the main path.
- One sentence per message; start with the problem, then the fix ('The old phone restarted. Connect a USB cable, or turn on automatic restart.').
- Buttons are verbs: Connect, Wake, Add old phone, Fix next item.
- Never blame the user; never use exclamation marks for errors.
- Numbers and units are explicit (6-digit code, 12 Mbps, 42 ms).
- All user-visible strings live in string resources with a translator comment; no concatenated sentences.

## Accessibility

- Every interactive element has a content description or visible label; decorative icons are hidden from TalkBack.
- Minimum touch target 48 dp; spacing on a 4 dp grid; primary actions reachable by thumb.
- Text and icon contrast meets WCAG AA in light and dark themes; status is never conveyed by colour alone.
- Layouts hold at 200% font scale and on small (360 dp) and large (tablet, foldable) windows using window size classes.
- The mirror viewer announces state changes (Connected, Reconnecting) through live regions and supports keyboard focus for the toolbox.
- The PIN pad and pairing code field use secure input where available and are excluded from screenshots and recents.
- Run Accessibility Scanner and a TalkBack walkthrough for every screen before the UI steps are considered done.
