# Dependencies

Record every dependency before using it. Check the licence text yourself; do not copy from memory.

| Name | Version | Licence | Used in | Reason | Date checked |
| --- | --- | --- | --- | --- | --- |
| Gradle Wrapper (`gradle`) | 9.9.2 | Apache-2.0 | Build toolchain | Current stable Gradle supporting AGP 9.5.x and JDK 17 daemon/toolchain | 2026-11-25 |
| Android Gradle Plugin (`com.android.application`, `com.android.library`) | 9.5.2 | Apache-2.0 | Android modules | Current stable AGP with built-in Kotlin and SDK 36 support | 2026-11-25 |
| Kotlin (`org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.compose`) | 2.4.21 | Apache-2.0 | `:protocol`, `:designsystem` | Current stable Kotlin JVM and Compose compiler plugins | 2026-11-25 |
| `androidx.core:core-ktx` | 1.18.0 | Apache-2.0 | `:app-viewer`, `:app-host` | AndroidX core KTX and `ViewCompat`/`WindowInsetsCompat` (`1.19.1` requires `compileSdk 37`) | 2026-11-25 |
| `androidx.appcompat:appcompat` | 1.8.0 | Apache-2.0 | `:app-viewer`, `:app-host` | Existing `AppCompatActivity` UI foundation | 2026-11-25 |
| `com.google.android.material:material` | 1.14.0 | Apache-2.0 | `:app-viewer`, `:app-host` | Existing Material views and `SwitchMaterial` components | 2026-11-25 |
| `androidx.compose:compose-bom` | 2026.06.01 | Apache-2.0 | `:designsystem` | Latest Compose BOM compatible with `compileSdk 36` (`2026.08.00+` requires `compileSdk 37`) | 2026-11-25 |
| `androidx.compose.ui:ui` | via BOM 2026.06.01 | Apache-2.0 | `:designsystem` | Compose UI runtime and layout primitives | 2026-11-25 |
| `androidx.compose.ui:ui-tooling` | via BOM 2026.06.01 | Apache-2.0 | `:designsystem` | Compose preview tooling (`debugImplementation`) | 2026-11-25 |
| `androidx.compose.ui:ui-tooling-preview` | via BOM 2026.06.01 | Apache-2.0 | `:designsystem` | Compose `@Preview` annotations | 2026-11-25 |
| `androidx.compose.material3:material3` | via BOM 2026.06.01 | Apache-2.0 | `:designsystem` | Material 3 Compose components and `MaterialTheme` | 2026-11-25 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache-2.0 | `:client-core` | Pure-JVM structured coroutines and `StateFlow` for `SessionEngine` and `MirrorClient` | 2026-11-27 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | `:app-viewer`, `:app-host` | Structured async IO and UI coroutines | 2026-11-25 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | Apache-2.0 | `:client-core` (`test`) | Virtual-time scheduler and coroutine leak detection for unit tests | 2026-11-27 |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.1 | Apache-2.0 | `:server-core` | Hidden Android framework API exemptions for mirror capture (`6.2` requires `compileSdk 37`) | 2026-11-25 |
| Spotless Gradle Plugin (`com.diffplug.spotless`) | 8.12.0 | Apache-2.0 | Root build | Automated Kotlin and Gradle Kotlin DSL formatting checks | 2026-11-25 |
| ktlint CLI (`com.pinterest.ktlint:ktlint-cli`) | 1.8.0 | MIT | Root Spotless | Kotlin style formatter/linter invoked by Spotless | 2026-11-25 |
| Detekt Gradle Plugin (`dev.detekt`) | 2.0.0-alpha.6 | Apache-2.0 | All modules | Static analysis (`io.gitlab.arturbosch.detekt:1.23.8` targets Gradle 8.x and breaks on Gradle 9/Kotlin 2.4) | 2026-11-25 |
| JUnit (`junit:junit`) | 4.13.2 | EPL-1.0 | `:protocol`, `:server-core`, `:client-core`, `:app-viewer` | Unit test runner | 2026-11-25 |
| AndroidX Test Runner (`androidx.test:runner`) | 1.6.1 | Apache-2.0 | `:server-core` (`androidTest`), `:client-core` (`androidTest`) | `AndroidJUnitRunner` for encoder/decoder instrumentation tests | 2026-11-25 |
| AndroidX Test JUnit (`androidx.test.ext:junit`) | 1.2.1 | Apache-2.0 | `:server-core` (`androidTest`), `:client-core` (`androidTest`) | `@RunWith(AndroidJUnit4::class)` instrumentation test runner | 2026-11-25 |
