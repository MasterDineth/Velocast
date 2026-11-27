# Shell-User Feasibility Spike (`S05`)

This directory contains the tooling to run `:server-core`'s `CapabilityProbe` on an Android device or emulator as the unprivileged ADB `shell` user (`uid 2000`) via `app_process`.

## Prerequisites

1. **Android SDK & ADB** installed and `adb` available on `PATH` (plus JDK 17+ for Gradle).
2. **Developer options** enabled on the target phone (**Settings → About phone → Build number** tapped 7 times).
3. **USB debugging** (or **Wireless debugging** paired with `adb pair`) enabled on the target phone.
4. **Authorised computer**: accept the *"Allow USB debugging?"* / *"Allow wireless debugging?"* prompt on the phone and confirm `adb devices -l` shows `device` (not `unauthorized` or `offline`).
5. On Xiaomi (MIUI / HyperOS) devices, keep note of whether **USB debugging (Security settings)** is enabled or disabled when recording input injection results.

## Usage

### Linux / macOS / Git Bash

```bash
./tools/spike/run-shell-server.sh
# Or specify a device serial when more than one device is connected:
./tools/spike/run-shell-server.sh -s <serial>
```

### Windows PowerShell

```powershell
.\tools\spike\run-shell-server.ps1
# Or specify a device serial when more than one device is connected:
.\tools\spike\run-shell-server.ps1 -Serial <serial>
```

The script:
1. Builds `:server-core:assembleDexJar` (`server-core/build/outputs/dex/velocast-server.jar`).
2. Pushes it to `/data/local/tmp/velocast-server.jar`.
3. Runs `com.mastersoft.velocast.server.CapabilityProbeMain` via `app_process` with `CLASSPATH=/data/local/tmp/velocast-server.jar`.
4. Pulls the JSON-lines output to `tools/spike/results/<model>-android<api>.json`.
5. Prints a summary table in the terminal.

## Recorded Results & Owner Template

Full analysis, per-API-level shim decisions, and session verification live in [`docs/spike-shell-user.md`](../../docs/spike-shell-user.md).

| Device / Model | ROM | Android / API | `virtual_display` | `input_injection` | `notification_shade` | `rotation` | `display_power` | `wake` | `clipboard` | Notes (e.g. OEM toggles) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `<model>` | `<AOSP / Stock>` | `11 (API 30)` | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | Covered by `PlatformApiPolicy` unit tests |
| `<model>` | `<AOSP / Stock>` | `13 (API 33)` | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | Covered by `PlatformApiPolicy` unit tests |
| `<model>` | `<AOSP / One UI>` | `14 (API 34)` | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | *Confirm on device* | Covered by `PlatformApiPolicy` unit tests |
| `Xiaomi M2101K6G` (Redmi Note 10 Pro) | Custom AOSP (`BP4A.251205.006`) | `16 (API 36)` | `supported` | `supported` | `supported` | `supported` | `supported` | `supported` | `supported` | Reference old phone; full shell session verified (`6f8d1516`) |
| `Xiaomi 25019PNF3C` (Xiaomi 15 Ultra) | Xiaomi HyperOS `V816` (`CP2A.260605.016`) | `17 (API 37)` | `supported` | `supported` | `supported` | `supported` | `supported` | `supported` | `supported` | `persist.security.adbinput=1` (*USB debugging (Security settings)* enabled); full shell session verified (`fba5cfe8`) |
