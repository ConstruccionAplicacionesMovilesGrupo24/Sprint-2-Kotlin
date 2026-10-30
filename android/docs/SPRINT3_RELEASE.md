# Sprint 3 / S3-13: release APK on Firebase App Distribution

The Sprint 3 build is delivered as a signed release APK through Firebase App Distribution, installed by the evaluators and the team on physical phones.

## Build

| Setting | Value |
|---|---|
| Application id | `com.campusmeal.android` |
| Version | `versionCode 2`, `versionName 0.3.0` |
| Backend | `https://campusmeal-api.onrender.com/api/v1/` (Render + Neon, see `docs/DEPLOYMENT_RENDER_NEON.md` in CampusMealBack) |
| Minification | Off (`isMinifyEnabled = false`), so kotlinx.serialization and Room need no R8 rules for this release |
| Logging | No HTTP logging interceptor in release |

`preReleaseBuild` fails unless the backend URL is `https://…/` and all four signing settings are present.

## Signing

The keystore and its passwords are **never** in the repository (`*.jks`, `*.keystore` and `local.properties` are gitignored). Each setting is read from an environment variable, a Gradle property or `android/local.properties`, like the API URL:

| Property | Environment variable |
|---|---|
| `campusmeal.release.storeFile` | `CAMPUSMEAL_RELEASE_STORE_FILE` |
| `campusmeal.release.storePassword` | `CAMPUSMEAL_RELEASE_STORE_PASSWORD` |
| `campusmeal.release.keyAlias` | `CAMPUSMEAL_RELEASE_KEY_ALIAS` |
| `campusmeal.release.keyPassword` | `CAMPUSMEAL_RELEASE_KEY_PASSWORD` |
| `campusmeal.releaseApiBaseUrl` | `CAMPUSMEAL_RELEASE_API_BASE_URL` |

One keystore is created once and kept by the person who publishes releases (outside the repo, with a backup). Every later version must be signed with the same key, or installed phones refuse the update.

```bash
keytool -genkeypair -v -keystore ~/keys/campusmeal-release.jks -alias campusmeal \
  -keyalg RSA -keysize 2048 -validity 10000
```

`android/local.properties`:

```properties
campusmeal.releaseApiBaseUrl=https://campusmeal-api.onrender.com/api/v1/
campusmeal.release.storeFile=/Users/<you>/keys/campusmeal-release.jks
campusmeal.release.storePassword=...
campusmeal.release.keyAlias=campusmeal
campusmeal.release.keyPassword=...
```

```bash
cd android
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Distribution

1. Firebase console -> project CampusMeal -> Android app `com.campusmeal.android` -> **App Distribution**.
2. Upload `app-release.apk`. Release notes: the Sprint 3 features (below).
3. Testers & Groups: group `evaluadores` with the evaluators' emails, plus the team. Distribute to the group.
4. Each tester accepts the email invitation, installs Firebase App Tester (or the web flow) and installs CampusMeal. Android asks to allow installs from that source.

The app does not include the Firebase SDK: App Distribution only needs the uploaded APK, so no `google-services.json` is added.

## Release notes (0.3.0)

- Accounts: per-account session and data, cleaned on logout or account switch (S3-02).
- Inventory and recipes (BQ2, BQ3) with offline cache.
- Meal decision (BQ5): offline "Previous comparison" history; selections and usage events queued in a Room outbox and delivered by WorkManager when online (S3-09, S3-11).
- Context (campus, time, budget, diet) kept after closing the app, without GPS coordinates.
- Analyst report BQ6 (feature adoption) for analyst accounts, cached per month.

## Verification on a physical phone

1. Install from the invitation; the app opens and the version in Android Settings is 0.3.0.
2. Log in with a demo account against the Render backend (the first request can take ~1 min while Render wakes up).
3. Compare meals, choose one in airplane mode, reconnect: the choice shows as sent.
4. Log in as the analyst: Profile -> Analytics shows the BQ6 report.
