# ADB Wireless Pairing and Connection — Manual Device Test Plan (`S07A`)

Status: Ready for owner verification on physical devices (`AGENTS.md` Rule 8 — **Confirm on device**).
Module under test: `:launcher-adb` (`AdbClient`, `AdbMdnsDiscovery`, `KeystoreProtectedAdbKeyStore`, `LocalNetworkPermissionGate`).

## Prerequisites

1. **Viewer phone**: Android 8.0+ (`API 26+`), including at least one Android 14–17 (`API 34–37`) phone to confirm `FUN-13` (no `ACCESS_LOCAL_NETWORK` dialog while `targetSdk = 36`).
2. **Old phone (unrooted)**: Android 11+ (`API 30+`, e.g. Android 11 and Android 16 reference phones per `S07` acceptance criteria), connected to the **same Wi-Fi network** as the Viewer phone, with **Developer options** enabled and no VeloCast Host app required.
3. Clean state before starting: on the old phone open **Settings -> System -> Developer options -> Wireless debugging** and tap **Forget all** under *Paired devices* (or revoke USB debugging authorisations).

---

## Test Cases

### M-S07A-01 — No premature `ACCESS_LOCAL_NETWORK` prompt (`FUN-13`)
- **Setup**: Fresh install of `:app-viewer` (`targetSdk = 36`) on an Android 14, 16, or 17 Viewer phone.
- **Action**: Launch VeloCast Viewer and start Wireless Debugging discovery (`AdbClientFactory.create(context)`).
- **Expected**:
  - Zero system permission prompt for `ACCESS_LOCAL_NETWORK`.
  - `LocalNetworkPermissionGate.status(context)` returns `IMPLICITLY_GRANTED_PRE_API_37`.
  - Local mDNS discovery (`_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`) and LAN TCP sockets operate normally.

### M-S07A-02 — Wireless pairing via QR code (`pairWithQr`)
- **Setup**: Both phones on the same Wi-Fi network; Wireless debugging **ON** on the old phone.
- **Action**:
  1. Invoke `adbClient.pairWithQr { payload -> ... }` and render `payload.encode()` (`WIFI:T:ADB;S:studio-...;P:...;;`) as a QR code on the Viewer screen.
  2. On the old phone: **Wireless debugging -> Pair device with QR code** and scan the QR code.
- **Expected**:
  - Old phone scans the QR code, advertises `<serviceName>._adb-tls-pairing._tcp`, Viewer resolves it via `NsdManager`, completes TLS 1.3 + SPAKE2 pairing, and `VeloCast Viewer` (`velocast@viewer`) appears under *Paired devices* on the old phone.
  - Logcat contains **zero** occurrences of the QR password (`<redacted>` only).

### M-S07A-03 — Wireless pairing via 6-digit code & mDNS discovery (`pair`)
- **Setup**: Forget the previous pairing on the old phone.
- **Action**:
  1. On the old phone: **Wireless debugging -> Pair device with pairing code** (note the displayed `IP:port` and 6-digit code).
  2. Start `adbClient.discovery.startDiscovery(AdbServiceType.TLS_PAIRING)` on the Viewer; confirm `AdbDiscoveryState.Found` resolves the matching `IP:port` (or enter `IP:port` via `useManualEndpoint`).
  3. First enter an intentionally wrong 6-digit code (`000000`), then retry with the displayed 6-digit code.
- **Expected**:
  - Wrong code fails with `AdbFailure.WrongPairingCode` (`causeKey = "error_wrong_pairing_code"`), with the attempted code scrubbed from logs/exceptions.
  - Correct 6-digit code succeeds (`AdbPairingResult`) and lists `velocast@viewer` under *Paired devices*.

### M-S07A-04 — TLS 1.3 connect (`STLS`), `shell`, `push`, and `openStream`
- **Setup**: Paired state from `M-S07A-02` or `M-S07A-03`.
- **Action**:
  1. Call `val device = adbClient.connect()` (auto-discovering `_adb-tls-connect._tcp`).
  2. Call `adbClient.shell("id")`.
  3. Call `adbClient.push(serverJarFile, "/data/local/tmp/velocast-server-test.jar")` and verify with `adbClient.shell("sha256sum /data/local/tmp/velocast-server-test.jar")`.
  4. Force-stop and relaunch the Viewer app, then call `adbClient.connect()` again.
- **Expected**:
  - `device.isTls == true`, `shell("id").stdout` contains `uid=2000(shell)`.
  - Remote SHA-256 matches the local file's SHA-256; cleanup removes the test file.
  - After Viewer restart, `KeystoreProtectedAdbKeyStore` reloads the persisted key pair/certificate from `noBackupFilesDir/velocast_adb_keys` and reconnects **without** re-pairing.

### M-S07A-05 — Dynamic port change when Wireless Debugging is toggled off and on
- **Setup**: Connected once at `host:portA`, then call `adbClient.disconnect()`.
- **Action**:
  1. On the old phone, toggle **Wireless debugging** **OFF**, wait 3 seconds, and toggle it back **ON** (verify Android assigned a new `portB != portA`).
  2. Call `adbClient.connect(host, portA)` passing the stale `portA` (and repeat with `adbClient.connect()` auto-discovery).
- **Expected**:
  - `adbClient.connect(host, portA)` catches `ECONNREFUSED` on `portA`, automatically discovers `portB` via `_adb-tls-connect._tcp`, and connects cleanly to `host:portB`!

### M-S07A-06 — Typed error mapping (`DEBUGGING_OFF`, `DIFFERENT_NETWORK`, `NOT_PAIRED`, `AUTH_EXPIRED`)
- **Action & Expected**:
  1. **Wireless debugging OFF** on old phone (same Wi-Fi): `adbClient.connect(host, lastPort)` and `adbClient.connect()` both fail with `AdbFailure.DebuggingOff` (`ErrorCode.DEBUGGING_OFF`).
  2. **Different network** (switch Viewer to mobile data or guest Wi-Fi on another subnet): `adbClient.connect(host, lastPort)` fails with `AdbFailure.DifferentNetwork` (`ErrorCode.DIFFERENT_NETWORK`).
  3. **Revoked / expired authorisation** (with `host` recorded as previously paired, tap **Forget** next to `velocast@viewer` on the old phone while Wireless debugging stays ON): `adbClient.connect(host, currentPort)` fails with `AdbFailure.AuthExpired` (`ErrorCode.AUTH_EXPIRED`).
  4. **Unpaired phone** (fresh Viewer key alias or never-paired second phone with Wireless debugging ON): `adbClient.connect(secondHost, secondPort)` fails with `AdbFailure.NotPaired` (`ErrorCode.NOT_PAIRED`).
