# BQ4 — Device context and location (Juan Pablo)

**BQ4:** which currently open restaurants are reachable within the user's available lunch time and
offer at least one meal within their budget and dietary preferences?

This document covers **Person A (Juan Pablo)'s part of Issue #3**: the approximate-location flow
and the manual campus fallback. Person B (Natalia) owns the Set Context screen, `MealContext`,
`ContextViewModel` and the construction of the search request; this part gives her the location
half of that request.

## Files

| File | Main types | Responsibility |
| --- | --- | --- |
| `feature/context/domain/MealLocation.kt` | `Campus`, `Coordinates`, `MealLocation`, `CampusCatalog` | The location input BQ4 needs, the provisional campus list and the coordinates → campus zone lookup. |
| `feature/context/LocationContextViewModel.kt` | `LocationContextUiState`, `LocationContextViewModel` | Permission flow, recent fix, manual campus selection, analytics. |
| `feature/context/LocationContextScreen.kt` | `LocationContextScreen`, `LocationContextSection`, `LocationContextContent` | Compose UI for every location state, following Figma screens 04, 13 and 14 (see `DESIGN_SYSTEM_ALIGNMENT.md`). |
| `core/location/FusedLocationProvider.kt` (modified) | `currentLocation()` | Now prefers a recent cached fix before requesting a new one. |
| `navigation/CampusMealNavHost.kt` (modified) | `CampusMealRoute.Context`, `HomePlaceholder` | Hosts the section standalone, and adds a temporary Home button that opens it. |
| `res/values/strings.xml` (modified) | `context_*` | English UI strings. |

`LocationProvider`, `FusedLocationProvider`, `AnalyticsTracker` and the two foreground location
permissions already existed from Issue #1; this change reuses them instead of adding a second
location boundary.

## How the flow works

1. **Opening the feature.** `LocationContextSection` calls `onContextOpened()` on first composition.
   If the permission is already granted it goes straight to the fix; otherwise it shows
   `PermissionRequired`, which explains *why* location improves the results before any system
   dialog appears.
2. **Permission request.** The button launches `RequestMultiplePermissions` for
   `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`. Only the coarse grant is required:
   approximate location is enough for BQ4. `ACCESS_BACKGROUND_LOCATION` is never requested.
3. **Getting a position.** `FusedLocationProvider` returns `lastLocation` when it is younger than
   five minutes, and otherwise requests one fix at `PRIORITY_BALANCED_POWER_ACCURACY`. A failed or
   absent fix is `LocationResult.Unavailable`; a `SecurityException` is `PermissionDenied`. The whole
   wait is capped at 8 seconds: Play Services leaves the request pending indefinitely when no
   provider ever answers, and without the cap the UI stays in `Loading` with no way out. A device
   with location switched off and a stock emulator both reproduce that.
4. **Resolving the location.** A device fix becomes `MealLocation.fromDevice(...)`, where the campus
   comes from `CampusCatalog.zoneFor()` — the nearest campus within 5 km, or null.
5. **Fallback.** Denial and unavailable location services show the campus chips directly under
   their banner; "Choose campus manually" and "Change" open `ManualCampusSelection`. Picking a campus produces `MealLocation.forCampus(...)`, whose
   coordinates are the campus centre, so the request always carries a usable position.

## UI states

| State | When | What the user sees |
| --- | --- | --- |
| `Initial` | Before the section is opened | The loading header |
| `PermissionRequired` | Permission missing | Screen 04: why location helps, "Enable location", "Choose campus manually" |
| `Loading` | While the fix is being obtained | Progress indicator |
| `LocationGranted` | A position was obtained | Screen 13: "Location enabled" banner, location card with "Change" |
| `PermissionDenied` | The user denied the permission | Screen 14: "No location access" banner, location card, campus chips |
| `ManualCampusSelection` | "Choose campus manually" or "Change" | Location card and campus chips; the choice is marked with ✓ |
| `Error` | Location services off or no fix | "Location unavailable" banner, campus chips and "Try again" |

`InvalidContext` is the eighth state required by the issue. It belongs to Natalia's
`ContextViewModel`, because it reports invalid available time or budget, which this part never
touches.

## Privacy

- The rationale is shown before the system dialog, not after.
- Denying the permission never blocks the flow: manual campus selection always remains available.
- **Coordinates never reach analytics.** `LocationContextViewModel` builds every event property
  from `Campus.zone` (`bogota-centro`, …) or the literal `unknown`. `MealLocation.analyticsZone`
  is the only location value meant for analytics.
- Device coordinates are held in memory by the ViewModel and are never written to Room, DataStore or
  any log. `clearPreciseLocation()` drops them once the request has been built and falls back to the
  campus centre, which is a fixed public point rather than the user's position, so a second search
  still works without keeping a personal location.
- Only foreground permissions are declared, as in Issue #1.

Events emitted: `context_location_granted` (`zone`), `context_location_permission_denied`,
`context_location_unavailable`, `context_campus_selected_manually` (`zone`). The tracker is still
`NoOpAnalyticsTracker`, so nothing is transmitted yet.

## Integration contract for Natalia

`LocationContextViewModel.mealLocation` is a `StateFlow<MealLocation?>`: null until a source is
resolved, then a value whose `coordinates` are always present — a device fix, or the centre of the
chosen campus when the device gives none. `source` says which, and `campusId` is null when no known
campus is nearby. Map it onto the search request body as:

```kotlin
location = LocationDto(mealLocation.coordinates.latitude, mealLocation.coordinates.longitude),
campusId = mealLocation.campusId,
```

This satisfies "at least one location source is available". The rest of the body —
`availableMinutes`, `maximumBudget`, `dietaryPreferences`, `includeDelivery` and `requestedAt`
generated by the app — belongs to `ContextViewModel`. Call `clearPreciseLocation()` right after the
request is built: it drops a device fix and falls back to the campus centre.

`LocationContextSection` is a section rather than a screen so the Set Context screen can host it
above the form inputs. Until that screen exists it is registered on `CampusMealRoute.Context`, reached
from a temporary "Set my lunch context" button on the Home placeholder. That registration and that
button are the only reason `CampusMealNavHost` was touched, and both are expected to be replaced.

## Validation

No automated tests: this prototype is validated by compilation, lint and manual runs.

| Command | Result |
| --- | --- |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew :app:lintDebug` | BUILD SUCCESSFUL. 0 errors, the same 5 pre-existing warnings (pinned versions and `OldTargetApi`) |

Run by hand on the `Tusky_API_36` emulator (API 36, `google_apis`), from the Home button
"Set my lunch context":

| Path | Result |
| --- | --- |
| `PermissionRequired` | The rationale is shown before the system dialog. Confirmed. |
| System dialog | Offers Precise / Approximate and While using the app / Only this time / Don't allow. Confirmed. |
| `PermissionDenied` | Denying shows the explanation and the campus picker; the flow continues. Confirmed. |
| `Error` | After the 8-second cap the screen offers the campus picker and "Try again". Confirmed. |
| Campus selection | Picking Javeriana, then Nacional, updates the card and the ✓ chip; the denied banner stays. Confirmed. |
| `LocationGranted` | Granting "Approximate" shows "Location enabled · Uniandes · approximate accuracy of 2000 m". Confirmed on a later boot — see below. |

`LocationGranted` is intermittent on this AVD. On a cold boot with `-no-snapshot-load`, Play
Services never resolved a position: `dumpsys location` reported every provider `OFF` with
`last location=null`, the log showed `ChimeraUtils: Module com.google.android.gms.location_base
missing resource`, and the request only ended through the 8-second cap. On a later normal boot the
same build returned an approximate fix immediately. The cause was not isolated, so if `Error` appears
where `LocationGranted` is expected, restart the emulator (or use a physical device) before
suspecting the code. Keep `PRIORITY_BALANCED_POWER_ACCURACY`: BQ4 only needs an approximate position.

## Pending

- `CampusCatalog` is a provisional hardcoded list. Replace it with the backend campus endpoint when
  that contract exists; the ids are the `campusId` values the search request sends.
- `POST /api/v1/restaurants/search` is not called from this part. The API interface, DTOs and
  repository belong to the search issue.
- Settings deep link for a permanently denied permission: the campus fallback covers the prototype.
