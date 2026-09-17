# CampusMeal Android

Native Android client for CampusMeal, built with Kotlin and Jetpack Compose.

This is the **bootstrap foundation**. It holds the project configuration, dependencies, an app shell and the architecture boundaries. None of the product screens exist yet.

## Requirements

| Tool | Version |
| --- | --- |
| Android Studio | A release that supports AGP 9.3 (tested with Android Studio 2026.1.3) |
| JDK (Gradle JDK) | **17** |
| Android SDK Platform | API 37 (`platforms;android-37.0`) for compilation |
| Target SDK | API 36 |
| Minimum SDK | API 24 |
| Gradle | 9.5.0 (via the wrapper; do not use a system Gradle) |

`gradle/gradle-daemon-jvm.properties` requires a Java 17 daemon.

- **Android Studio:** open *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* and choose a JDK 17. If none is installed, pick *Download JDK… → version 17*.
- **Command line:** Gradle picks a detected JDK 17 for the daemon (for example one under `~/.jdks`). If none is detected, set `JAVA_HOME` to a JDK 17 installation.

Command-line builds also need the Android SDK location. Android Studio writes it to `android/local.properties` (`sdk.dir=...`) on the first sync. Without Studio, set `ANDROID_HOME` or create that file yourself; otherwise Gradle fails with "SDK location not found".

If the API 37 platform is missing and the SDK licenses have been accepted, AGP downloads it on the first build. Otherwise install it from *SDK Manager*, or run `sdkmanager "platforms;android-37.0"`.

## Version matrix

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 9.3.2 |
| Gradle wrapper | 9.5.0 |
| Kotlin (Compose and Serialization compiler plugins) | 2.4.20 |
| KSP | 2.3.12 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 |
| Java source / target, Kotlin JVM target | 17 |

All versions are declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Project structure

The prototype uses one `:app` module. The package layout keeps the boundaries clear so features can be split into modules later if needed.

```
android/
├── build.gradle.kts            Root plugins (apply false)
├── settings.gradle.kts         Repositories and module list
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml      Version catalog
│   ├── gradle-daemon-jvm.properties
│   └── wrapper/                Gradle 9.5.0 wrapper
└── app/
    ├── build.gradle.kts
    ├── schemas/                Exported Room schemas (commit these)
    └── src/
        ├── main/java/com/campusmeal/android/
        │   ├── app/            Application, MainActivity, CampusMealApp, AppContainer
        │   ├── core/
        │   │   ├── analytics/  AnalyticsTracker boundary (no-op for now)
        │   │   ├── common/     UiState
        │   │   ├── database/   Room database shell and cache metadata
        │   │   ├── datastore/  Preferences DataStore instance
        │   │   ├── designsystem/ CampusMealTheme, temporary color/type tokens
        │   │   ├── location/   LocationProvider boundary (fused, foreground only)
        │   │   ├── network/    NetworkConfig, ApiClientFactory (OkHttp + Retrofit), ApiResult/apiCall
        │   │   └── session/    SessionStorage boundary and SessionRepository
        │   └── navigation/     Type-safe route contract and NavHost
        └── debug/res/xml/      Debug-only network security config (local cleartext)
```

Contribution notes for individual issues live in [`docs/`](docs/).

Feature packages (`feature/auth`, `feature/inventory`, `feature/context`, `feature/restaurants`, `feature/decision`, `feature/profile`) are created along with their first real source files. Empty placeholder packages are not kept.

## Build and validate

Run all commands from the `android/` directory. On Windows, use `gradlew.bat` or `.\gradlew`.

```bash
./gradlew --version                         # confirm Gradle 9.5.0 and a JDK 17 daemon
./gradlew :app:assembleDebug                # build the debug APK
./gradlew :app:lintDebug                    # Android lint
```

## Prototype validation

This project currently prioritizes rapid prototype development. Automated unit,
instrumented and Compose tests are not included. Validation is performed through:

- Successful debug compilation.
- Android lint.
- Manual execution of the main application flows on an emulator or device.

## Backend URL configuration

The app only talks to the **CampusMeal NestJS API**. External services such as the route provider are always reached through that API (Android → CampusMeal API → external route provider). Never call them directly, and never put their credentials in this project.

The base URL goes into `BuildConfig.API_BASE_URL` at build time and reaches the code only through `NetworkConfig`. Values must end with `/`.

Each setting is looked up in this order, and the first non-blank value wins:

1. Environment variable
2. Gradle property (`-P...` or `~/.gradle/gradle.properties`)
3. `android/local.properties` (untracked)

| Build type | Property | Environment variable | Fallback |
| --- | --- | --- | --- |
| debug | `campusmeal.apiBaseUrl` | `CAMPUSMEAL_API_BASE_URL` | `http://10.0.2.2:3000/api/v1/` |
| release | `campusmeal.releaseApiBaseUrl` | `CAMPUSMEAL_RELEASE_API_BASE_URL` | none; the build fails |

### Local emulator (default)

With the NestJS API on port 3000 of your machine, no configuration is needed. `10.0.2.2` is the emulator's alias for the host loopback.

### Local override

Add this to `android/local.properties` (Android Studio creates the file, and it is gitignored):

```properties
campusmeal.apiBaseUrl=http://localhost:3000/api/v1/
```

For a physical device over USB, run `adb reverse tcp:3000 tcp:3000` and use the `localhost` URL above.

Debug builds allow cleartext HTTP only to `10.0.2.2`, `localhost` and `127.0.0.1` (see `app/src/debug/res/xml/network_security_config.xml`). Every other host requires HTTPS.

### CI

- **Debug builds in CI:** set `CAMPUSMEAL_API_BASE_URL` in the pipeline environment.

### Production (future)

Release builds require `campusmeal.releaseApiBaseUrl` or `CAMPUSMEAL_RELEASE_API_BASE_URL`, set to an `https://` URL. The `preReleaseBuild` task fails otherwise. For that reason, `./gradlew build` (which also assembles release) needs this value. Set it only in CI secrets or your local environment, never in tracked files.

## Dependency choices

| Area | Libraries | Why |
| --- | --- | --- |
| UI | Compose BOM 2026.08.00, Material 3, Activity Compose, Core KTX | Compose artifacts take their versions from the BOM, so no individual versions are declared |
| Lifecycle and navigation | Lifecycle 2.11.0 (runtime, runtime-compose, viewmodel-compose), Navigation Compose 2.10.1 | Lifecycle-aware state collection; type-safe `@Serializable` routes |
| Async and JSON | Coroutines 1.11.0, kotlinx.serialization JSON 1.11.0 | Kotlin-first; no reflection-based JSON |
| Network | Retrofit 3.0.0 with the kotlinx-serialization converter, OkHttp logging-interceptor 4.12.0 | Retrofit 3.0.0 and Coil's OkHttp integration both use OkHttp 4.12.0, so the versions line up |
| Location | Play Services Location 21.4.0 | Fused provider; foreground permissions only |
| Persistence | Room 2.8.5 (compiler via **KSP**), DataStore Preferences 1.2.1, WorkManager 2.11.2 | Offline cache, preferences, future background sync |
| Images | Coil 3.6.2 (compose, network-okhttp) | Compose-native image loading on the shared OkHttp stack |

The project intentionally does **not** include Hilt, Firebase Authentication, Google Maps, CameraX, ML Kit or kapt.

### Why AGP built-in Kotlin

AGP 9 compiles Kotlin itself. The `org.jetbrains.kotlin.android` plugin is therefore not needed and is not applied. Only these Kotlin compiler plugins are added: Compose, Serialization, and KSP for Room. This keeps the plugin list short and avoids version drift between AGP and a separately applied Kotlin Android plugin. kapt is not used; all annotation processing goes through KSP.

### Why manual dependency injection

`AppContainer` (`app/AppContainer.kt`) is a small interface with one `DefaultAppContainer` implementation, created in `CampusMealApplication`. For a single-module prototype with few dependencies, this:

- adds no annotation processing or build time
- is easy for the whole team to read
- keeps dependencies swappable behind small interfaces

Features should receive dependencies through constructors, not by reaching into the container. Moving to Hilt later then only changes the wiring. Revisit this choice when the dependency graph or the module count grows.

## Security notes

- **HTTP logging:** debug builds only, at `HEADERS` level. `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` and `X-Refresh-Token` are redacted. Bodies are never logged because they can contain credentials, JWTs or refresh tokens. Release builds install no logging interceptor.
- **Backups:** disabled (`android:allowBackup="false"`). `backup_rules.xml` and `data_extraction_rules.xml` also exclude all app data from backup and device transfer.
- **Location:** foreground only; `ACCESS_BACKGROUND_LOCATION` is not requested.
- **Secrets:** no API keys or secrets are committed. `local.properties`, keystores and build outputs are gitignored.
- **Plaintext storage:** Room and DataStore are plaintext and must not hold tokens.

### Pending: secure session storage

`SessionStorage` is the only place tokens may be stored. The current implementation, `InMemorySessionStorage`, keeps tokens **in memory only**: they are never written to disk and are lost when the process dies. It is **not** the final design.

Before real login ships, replace it with an implementation that encrypts tokens with a key held in the **Android Keystore** and persists only the ciphertext. That work also needs to cover:

- key invalidation
- the refresh-token flow against the CampusMeal API
- clearing data on sign-out

## Out of scope for this bootstrap

- Login, Inventory, Context, Restaurants, Cook/Walk/Order decision and Profile screens, with their ViewModels
- API service interfaces, DTOs and repositories for specific endpoints
- Authorization header interceptor and token refresh
- Keystore-backed `SessionStorage`
- Room feature entities, DAOs and migrations
- Analytics submission to the CampusMeal API (the tracker is currently a no-op)
- Runtime location permission UI
- WorkManager sync jobs and Coil image loader configuration
- Release signing, R8/minification rules and the production backend URL
- Final brand palette, typography and launcher icon
