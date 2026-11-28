# ADB Client Library Decision (`S07A`)

Status: Accepted (`S07A`, branch `step/S07a-adb-pair`).
Module: `:launcher-adb` (`com.mastersoft.velocast.launcher.adb`).

## 1. Context and Requirements

VeloCast's primary rescue launcher (`D1`, `SEC-04`) connects from the Viewer phone to the old phone's `adbd` over Android 11+ Wireless Debugging (and in `S07C`, over USB OTG) with nothing pre-installed on the old phone. `:launcher-adb` therefore needs an implementation behind our own swappable `AdbClient` interface supporting:

1. **Wireless pairing (`_adb-tls-pairing._tcp`)**: TLS 1.3 handshake with self-signed RSA-2048 X.509 client certificate, RFC 5705 keying-material export (`"adb-label\u0000"`, 64 bytes), BoringSSL-compatible SPAKE2 over Edwards25519 (`"adb pair client\u0000"` / `"adb pair server\u0000"`), HKDF-SHA256 (`"adb pairing_auth aes-128-gcm key"`), and AES-128-GCM `PeerInfo` exchange, triggered either by a 6-digit code (`pair`) or by standard QR code (`pairWithQr`, `WIFI:T:ADB;S:<service>;P:<password>;;`).
2. **Wireless TLS connection (`_adb-tls-connect._tcp`)**: ADB `A_CNXN` -> `A_STLS` (`0x534c5453`) TLS 1.3 mutual-certificate upgrade, plus legacy `A_AUTH` RSA token signing (`ADB_AUTH_SIGNATURE` / `ADB_AUTH_RSAPUBLICKEY`) for non-TLS transports.
3. **Multiplexed ADB services**: `shell:` / `shell,v2,raw:`, `sync:` (`push`), and raw bidirectional `openStream(service)` (`app_process` stdin/stdout lifetime guard and `tcp:<port>` tunnel in `S07B`).
4. **USB OTG readiness (`S07C`)**: Packet-preserving framing suitable for `UsbDeviceConnection.bulkTransfer` (ADB USB requires each 24-byte header and each non-empty payload to be transmitted as distinct USB bulk packets rather than coalesced TCP byte streams).
5. **Supply-chain, SDK, and licence constraints**: VeloCast is distributed under **Apache-2.0** (`LICENSE`), builds reproducibly from Google Maven + Maven Central (`settings.gradle.kts` with `FAIL_ON_PROJECT_REPOS`), targets `minSdk = 26` / `compileSdk = 36` (`BLD-02`), forbids hidden-API bypass in `:app-viewer`, and must pass R8 full-mode minification in `S17` for GitHub Releases, F-Droid, and Play evaluation.

---

## 2. Candidate Comparison

| Criterion | 1. `libadb-android` (`MuntashirAkon/libadb-android` `3.1.1`) | 2. `dadb` (`dev.mobile:dadb` `2.0.0`) + Wireless-Pairing Fork (`com.flyfishxu:kadb` `2.1.5`) or Extension | 3. Minimal In-House Client on Vetted Maven Central Crypto (`conscrypt`, `bouncycastle`, `curve25519-kotlin`) |
| --- | --- | --- | --- |
| **Wireless pairing & TLS 1.3** | Built-in `PairingConnectionCtx` (SPAKE2 + RFC 5705 exporter + AES-128-GCM `PeerInfo`) and `AdbConnection` `A_STLS` upgrade. **Defects found in `3.1.1`**: `SslUtils` caches one static `SSLContext` per process (key rotation / multi-alias testing silently reuses the first key); `AdbConnection.Builder.connect()` has an inverted boolean throw; no built-in QR pairing coordinator; no `sync:` (`push`) client. | **Stock `dadb` `2.0.0`**: **No** wireless pairing and **no** `A_STLS` support (`AdbConnection.connect` rejects any reply to `CNXN` other than `AUTH` or `CNXN`, failing immediately against Android 11+ `_adb-tls-connect._tcp`; `AdbConnection` is `internal` and stream-bound so `STLS` socket upgrade cannot be added externally without forking). **Fork `Kadb` `2.1.5`**: Adds pairing & `STLS`, but pulls `org.lsposed.hiddenapibypass` into the viewer runtime and depends on JitPack `spake2-java`. | Full support in `:launcher-adb`: per-identity Conscrypt `TLSv1.3` `SSLContext`, public `Conscrypt.exportKeyingMaterial`, BoringSSL-compatible SPAKE2 using `curve25519-kotlin` constant-time Edwards25519 primitives, HKDF-SHA256 (`:protocol`), JCA `AES/GCM/NoPadding`, `pair(host, port, code)`, `pairWithQr`, `A_STLS` upgrade, `shell`/`shell,v2,raw`, `sync:` `push`, and `openStream`. |
| **USB OTG transport (`S07C`)** | **Unsupported.** `AdbConnection` hardcodes `new java.net.Socket(host, port)`. Adding `UsbDeviceConnection` requires replacing or forking `AdbConnection`. | **Stock `dadb` `2.0.0`**: Desktop USB only via host `adb` server (`AdbServer`); internal `AdbReader`/`AdbWriter` wrap byte-stream Okio `Source`/`Sink`, losing the 24-byte header / payload USB packet boundaries required by `adbd` over `bulkTransfer`. **Fork `Kadb` `2.1.5`**: Adds USB, but compiled for `compileSdk = 37`. | **First-class.** Multiplexer operates over `AdbPacketTransport` (`readPacket` / `writePacket`), mapping cleanly to TCP/TLS sockets in `S07A` and directly to two `UsbDeviceConnection.bulkTransfer` calls per packet in `S07C` with zero protocol rewrite. |
| **Maintenance & distribution** | **JitPack-only** (`com.github.MuntashirAkon:libadb-android:3.1.1`, **404 on Maven Central**). Single maintainer; upstream README carries an explicit *"never gone through a security audit"* disclaimer; `spake2-android` is an AAR so pure-JVM unit tests cannot run pairing without Robolectric/devices. | **Stock `dadb` `2.0.0`**: Actively maintained by `mobile.dev` on Maven Central (released June 2026). **Fork `Kadb` `2.1.5`**: On Maven Central (`Oct 2026`), **but** its POM requires `com.github.Flyfish233:spake2-java:1.1.1` which is **JitPack-only** (breaks Maven Central–only builds) and requires `compileSdk = 37` (breaks VeloCast's `compileSdk = 36`). | **100% Maven Central**, actively maintained foundations: Google `org.conscrypt` `2.10.0` (Nov 2026), Legion of the Bouncy Castle `1.87` (Nov 2026), `curve25519-kotlin` `0.0.8`. Pure-JVM unit tests run real TLS 1.3 + SPAKE2 + `STLS` handshakes via `conscrypt-openjdk-uber`. |
| **Licences (exact SPDX)** | `libadb-android`: `BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)`. Mandatory transitive `spake2-android:2.2.1`: **`LGPL-3.0-or-later`**. Optional X.509 helper `sun-security-android:1.1`: **`GPL-2.0-only`**. Transitive `bcprov`: `MIT`; optional `conscrypt-android`: `Apache-2.0`. | **Stock `dadb` `2.0.0`**: `Apache-2.0` + `com.squareup.okio:okio:2.10.0` (`Apache-2.0`). **Fork `Kadb` `2.1.5`**: `Apache-2.0`, `bcprov`/`bcpkix` (`MIT`), `okio` (`Apache-2.0`), `hiddenapibypass` (`Apache-2.0`), **plus mandatory transitive `Flyfish233:spake2-java:1.1.1` (`LGPL-3.0-or-later`)**. | VeloCast code: `Apache-2.0`. `org.conscrypt:conscrypt-android:2.10.0` (+ test `conscrypt-openjdk-uber:2.10.0`): **`Apache-2.0`**. `org.bouncycastle:bcprov-jdk18on:1.87`, `bcpkix-jdk18on:1.87`, `bcutil-jdk18on:1.87`: **`MIT`** (Bouncy Castle Licence). `io.github.andreypfau:curve25519-kotlin:0.0.8` (+ `kotlinx-crypto-*`): **`Apache-2.0`**. |
| **Size impact** | `libadb` (~45 KB) + `spake2-android` (~50 KB) + `bcprov` (~4.2 MB JAR before R8) + `conscrypt-android` (~4.5 MB native `.so`/ABI; ~1.4 MB compressed in APK split) = **~1.8–6.0 MB** post-R8 depending on ABI splits. | Stock `dadb` (~85 KB) + `okio` (~350 KB) = ~435 KB, **plus** if extended for pairing/TLS: `conscrypt-android` (~1.4 MB compressed/ABI) + `bcprov`/`bcpkix` + SPAKE2 (~300 KB post-R8) = **~2.1 MB** post-R8 per ABI. | `:launcher-adb` protocol/client (~35 KB) + `curve25519-kotlin` (~95 KB) + `bcpkix`/`bcprov` (shrunk by R8 to X.509v3 + RSA paths, ~250 KB) + `conscrypt-android` (~1.4 MB compressed/ABI) = **~1.8 MB** post-R8 per ABI. |
| **API level support** | Library `minSdk = 1` (`compileSdk = 35`); TLS 1.3 + exporter works on `minSdk = 26` only when `conscrypt-android` is bundled (platform Conscrypt requires API 29+ and private-API reflection). | Stock `dadb`: JVM 8+ (`minSdk = 21+`, no TLS). Fork `Kadb` `2.1.5`: `minSdk = 23`, **`compileSdk = 37`** (incompatible with VeloCast's `compileSdk = 36` in `S01`–`S16`). | Full support across VeloCast's entire `minSdk = 26` .. `targetSdk = 36` (and API 37+) matrix with **zero** hidden-API reflection; wireless pairing/debugging targets Android 11+ (`adbd` API 30+) old phones, and legacy `A_AUTH` works down to API 26 over TCP/USB. |

---

## 3. Exact Licence Obligations Audit

All licence texts were inspected directly from upstream repositories and Maven Central POMs on `2026-11-28`:

1. **`Apache-2.0`** (`dadb`, `okio`, `conscrypt-android`, `conscrypt-openjdk-uber`, `curve25519-kotlin`, `kotlinx-crypto-*`, VeloCast):
   - **Obligations (Apache-2.0 §4)**:
     - Provide recipients a copy of the Apache License 2.0.
     - Retain all copyright, patent, trademark, and attribution notices in source distributions.
     - Reproduce attribution notices from any upstream `NOTICE` file in documentation or the in-app *About and licences* screen (`S17`).
     - State prominent change notices on any modified upstream files if forked/vendored.
   - **Compatibility**: Fully permissive; allows static DEX merging and R8 minification/obfuscation in Apache-2.0 and proprietary/store distributions.

2. **`MIT` / Bouncy Castle Licence** (`bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on`):
   - **Verified text**: Identical in substance to the MIT License (`Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.`).
   - **Obligations**: Include the copyright notice and permission/warranty notice in all copies or substantial portions of the software (satisfied via `NOTICE` and the `S17` in-app licences screen).
   - **Compatibility**: Fully permissive and compatible with Apache-2.0 and R8 shrinking.

3. **`BSD-3-Clause`** (portions of `libadb-android` derived from Cameron Gutman's `AdbLib`):
   - **Obligations**: Retain copyright notice, conditions list, and disclaimer in source and binary distributions (in documentation/about screen); do not use copyright holder names for endorsement without permission.
   - **Compatibility**: Compatible with Apache-2.0.

4. **`LGPL-3.0-or-later`** (`MuntashirAkon/spake2-java` used by `libadb-android`, and `Flyfish233/spake2-java` used by `Kadb`):
   - **Obligations (LGPL-3.0 §4 "Combined Works")**: When statically merged into an Android `classes.dex` and minified with R8 (where unrooted Android provides no user-replaceable dynamic shared library mechanism under §4(d)(1)), distributing the APK requires:
     1. Prominent notice that the LGPL library is used and covered by LGPL-3.0, plus copies of both **GPL-3.0** and **LGPL-3.0** (§4(a)–(c)); **and**
     2. Under §4(d)(0), conveying the **Minimal Corresponding Source** *and* **Corresponding Application Code** (or relinkable object/DEX artifacts and build scripts) under terms permitting any recipient to modify the LGPL library, re-run R8/DEX linking, and re-sign/install the modified Combined Work (plus Installation Information under §4(e) where applicable).
   - **Impact on VeloCast**: Conflicts with distributing VeloCast cleanly under pure `Apache-2.0` (`LICENSE`) without imposing LGPL-3.0 §4(d) relinking obligations on downstream redistributors (explicitly noted by `libadb-android`'s upstream README as incompatible with Apache Software Foundation licensing policy).

5. **`GPL-2.0-only`** (`MuntashirAkon/sun-security-android`):
   - **Obligations**: Strong copyleft requiring the entire derivative work to be licensed under GPL-2.0 with complete corresponding source code.
   - **Impact on VeloCast**: **Mutually incompatible with `Apache-2.0`** (Apache-2.0 §3 patent termination and indemnification clauses are rejected by GPL-2.0 §6/§7); cannot be linked into VeloCast.

---

## 4. Recommendation and Architecture Decision

### Decision: **Option 3 — Minimal in-house ADB client behind `AdbClient`, built on vetted Maven Central crypto & TLS libraries**

Why Option 3 wins decisively over `libadb-android` and `dadb` (+ fork):

1. **Zero copyleft / zero JitPack risk**: Every runtime and test dependency (`conscrypt-android`, `bcpkix-jdk18on`, `bcprov-jdk18on`, `curve25519-kotlin`) comes from **Maven Central** under **`Apache-2.0` or `MIT`**, keeping VeloCast 100% Apache-2.0 compliant, F-Droid reproducible (`S17`), and free of LGPL-3.0 `spake2-java` or GPL-2.0 `sun-security-android`.
2. **Vetted cryptographic primitives only (`AGENTS.md` Rule 4)**:
   - **TLS 1.3 & RFC 5705 exporter**: Google's BoringSSL-backed `org.conscrypt:conscrypt-android:2.10.0` (`Conscrypt.exportKeyingMaterial`) — public API, zero hidden-API reflection on Android 8–17 (`minSdk 26`..`37`), and `conscrypt-openjdk-uber:2.10.0` for real TLS 1.3 JVM unit tests.
   - **X.509v3 certificate & RSA-2048**: Bouncy Castle `X509v3CertificateBuilder` + JCA `KeyPairGenerator("RSA")` + `AndroidKeyStoreEnvelopeCipher` at-rest encryption.
   - **Edwards25519 group & scalar math for SPAKE2**: `io.github.andreypfau:curve25519-kotlin:0.0.8` (`curve25519-dalek` port) for constant-time point decompression, cofactor clearing, scalar multiplication, and point addition/subtraction, paired with JCA `SHA-512`, `:protocol`'s RFC 5869 `HkdfSha256`, and JCA `AES/GCM/NoPadding`.
3. **Clean `S07A` -> `S07B` -> `S07C` architecture**:
   - All callers in `:launcher-adb` (`S07B` `AdbLauncher`, `PrepareRunner`) and `:app-viewer` (`S13`) depend strictly on the `AdbClient` and `AdbDiscovery` interfaces (`AdbClientFactory`).
   - Internally, `InHouseAdbClient` layers `AdbConnection` multiplexing over a packet-preserving `AdbPacketTransport` interface, so `S07A` uses TCP + in-place `A_STLS` TLS 1.3 upgrade while `S07C` plugs in `UsbDeviceConnection` bulk packet transfers without touching stream multiplexing, `shell`, `push`, or `openStream`.
   - Avoids forking `dadb` (whose `AdbConnection` lacks `A_STLS`, wireless pairing, and packet-boundary USB framing) and avoids `Kadb`'s `compileSdk 37` and viewer-side `hiddenapibypass` dependencies.
