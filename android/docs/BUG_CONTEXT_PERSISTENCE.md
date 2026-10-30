# Bug: the meal context was lost when the app was closed

**Symptom.** After setting campus, time, budget, diet and delivery, closing the app completely and
reopening it showed no context: Home had nothing, and Decide / Find restaurants sent the user back
to the context form.

**Cause.** `CampusMealNavHost` kept the validated context only in `remember { mutableStateOf(...) }`,
on purpose, so a device location fix would never be written to disk. `remember` dies with the
process, and nothing else stored the context. Connectivity was not involved.

## Fix

`feature/context/data/local/ContextPreferencesStore.kt` stores, per account
(`context.last.{accountId}` in the existing DataStore), only the non-precise part of the context:

| Stored | Not stored |
|---|---|
| available minutes, budget, dietary preferences, include delivery | latitude / longitude (never) |
| campus id: the manually chosen campus, or the campus zone a device fix fell in | the device fix itself |

On restart the context is restored and stays until the user sets a new one:

- **Campus (manual, or a device fix within 5 km of a campus):** the full context is rebuilt from
  `CampusCatalog` at that campus centre (public data) with `requestedAt = now`, so Home, Decide and
  Find restaurants work right away. A context from a device fix comes back at the campus centre, not
  at the exact position; walking times are estimated from there until the user sets a new context.
- **Location outside every campus:** there is no campus to rebuild from, so only the form (time,
  budget, diet, delivery) is pre-filled and the location is asked again.

*Update:* the first version restored only manually chosen campuses, so contexts set with the device
location were lost on restart. Values saved by that version (`manualCampusId`) are still read.

Writes go through `AccountSessionCoordinator.runIfCurrent`, so a late write never lands in another
session. The store is an `AccountScopedStore` registered in `AccountDataCleaner`: deleted on logout
and when another account is activated. `ContextViewModel` pre-fills the saved form without
overriding anything the user already typed.

## Manual verification

1. Manual campus: set the context, close the app completely, reopen: Home shows the context and
   Decide opens the comparison directly.
2. Device location in Bogotá (on the emulator: Extended controls → Location, e.g. 4.6025, -74.0653):
   set the context, close and reopen: the context is still there, at the campus centre.
3. Device Explorer, `/data/data/com.campusmeal.android/files/datastore/campusmeal_preferences.preferences_pb`:
   contains `context.last.<accountId>` with no latitude or longitude.
4. Log out and sign in with another account: the previous account's context does not appear.
