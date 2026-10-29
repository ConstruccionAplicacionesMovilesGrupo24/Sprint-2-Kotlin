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
| campus id, **only** when the location was a manually chosen campus centre | device fixes, even inside a campus zone |

On restart:

- **Manual campus:** the full context is rebuilt from `CampusCatalog` (the campus centre is public
  data) with `requestedAt = now`, so Home, Decide and Find restaurants work right away.
- **Device location:** only the form (time, budget, diet, delivery) is pre-filled; the user confirms
  the location again.

Writes go through `AccountSessionCoordinator.runIfCurrent`, so a late write never lands in another
session. The store is an `AccountScopedStore` registered in `AccountDataCleaner`: deleted on logout
and when another account is activated. `ContextViewModel` pre-fills the saved form without
overriding anything the user already typed.

## Manual verification

1. Manual campus: set the context, close the app completely, reopen: Home shows the context and
   Decide opens the comparison directly.
2. Device location: set the context with location granted, close and reopen: the form keeps time,
   budget, diet and delivery, and the location is requested again.
3. Device Explorer, `/data/data/com.campusmeal.android/files/datastore/campusmeal_preferences.preferences_pb`:
   contains `context.last.<accountId>` with no latitude or longitude.
4. Log out and sign in with another account: the previous account's context does not appear.
