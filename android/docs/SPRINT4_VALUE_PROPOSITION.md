# Sprint 4 — Final value proposition against collected evidence (S4-12)

Issue: [S4-12 / #42](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/42).
Owner: Juan Pablo (@bedoya0611). Reviewer: Natalia (@NataliaEspitia).
Baseline: Android `main` `fc7a442`, CampusMealBack `main` `1910b13`, backend on Render + Neon
(`https://campusmeal-api.onrender.com/api/v1`). Original proposition:
[Sprint 1 wiki §1.2–1.4, revision `1a0c6ab`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint1/wiki/SPRINT-1/1a0c6ab3e54ad5b96d6eca7c86399a04c42acaa1).

**Status (2026-11-26).** Evidence from the three members is consolidated: Juan Pablo (S4-01, S4-03 to S4-05),
Daniel (S4-10, S4-11) and Natalia (S4-06, S4-07, [SPRINT4_NATALIA.md](SPRINT4_NATALIA.md)). Still open: the
reviewer's reproduction (§8) and, from the owners' own documents, a positive alternative on a device (Natalia §6)
and the in-place upgrade from the release-signed 0.4.0, which needs the release key (S4-14).
**No pilot with students was run**: no consenting participants were available. Every interaction below was made
by team members, demo accounts or the disposable test account, so it is **functional validation**, not evidence of
student behavior.

## 1. Evidence labels

Every number in this document carries one of these labels.

| Label | Meaning | May support |
|---|---|---|
| **REAL-TEAM** | Real requests to the production backend, made by team, demo or test accounts through the app or the public API | That a flow works end to end and what the backend computes. Not adoption, preference or value |
| **SEED** | Deterministic synthetic rows written by a backend seed, labeled `synthetic` in the database and the reports | That a report computes and presents the expected result |
| **STRESS** | Generated fixture used only for profiling (`Bq3SyntheticDataset`) | Performance under load |
| **PILOT** | Observations of consenting students | None collected (§6) |

Report snapshots are stored unchanged in
[`evidence/sprint4/s4-12-value/render-2026-11-25/`](evidence/sprint4/s4-12-value/render-2026-11-25/README.md)
(read-only analyst `GET`s, 2026-11-25 21:05 Bogotá) and
[`render-2026-11-26/`](evidence/sprint4/s4-12-value/render-2026-11-26/README.md) (BQ10 cross-check of Natalia's
device run, 2026-11-26 10:58 Bogotá). Later events change the numbers.

## 2. Original proposition in short

Sprint 1 promised a free meal-decision assistant for students that combines inventory, expiration dates,
preparation time, menus, prices, walking times, diet and reviews to compare **Cook / Walk / Order**, with five
benefits: faster decisions, less food waste, better spending control, restaurant confidence and context-aware
recommendations. Restaurants would pay for a freemium B2B offer: subscriptions, labeled sponsored promotions,
aggregated analytics and institutional partnerships.

## 3. Claim → feature → BQ → data → result → limitation

| # | Claim (Sprint 1) | Implemented feature and source | BQ | Data and label | Observed result | Limitation |
|---|---|---|---|---|---|---|
| C1 | **Faster meal decisions** from one screen | Cook/Walk/Order comparison: backend scores and explains, the app shows it unchanged (`feature/decision/`, [BQ5 data doc](BQ5_MEAL_DECISION_DATA.md)); recent decision restored offline (S3); load telemetry (S4-10, [Daniel](SPRINT4_DANIEL.md) §3.1) | BQ5, BQ1 | BQ1 real window 2026-11-01…25: 25 events, 2 device/version combinations (**REAL-TEAM**). Emulator against a local backend: `FRESH_NETWORK` 572 ms, `CACHED_RESTORE` without a request (Daniel §5) | One screen with three ranked alternatives and an explanation exists. Real fresh loads: **1** (2,001 ms, Render verification device), labeled `INSUFFICIENT` (needs ≥ 20) | No decision-time measurement with users, so **no claim that decisions are faster**. Delivery fees and times are fixed catalog values (`restaurants.seed.ts`), not quotes from a delivery service |
| C2 | **Time and budget compatibility** | Restaurant search filters open restaurants by budget and diet, with walking times from Valhalla routing on Render (`restaurants.service.ts`, `ROUTE_PROVIDER_MODE=valhalla`); BQ3 maximum preparation time (`RecipeEvaluator.kt`); comparison request carries `availableMinutes` and `maximumBudget`. *Alternative meals* (S4-07): restaurant results → Find alternatives, another category that keeps the same budget, diet and time (`feature/bq10/`, `POST restaurants/alternatives`) | BQ4, BQ3, BQ5, BQ8, BQ10 | BQ8 2026-10-01…11-25 (**REAL-TEAM**, no seed writes typed explanations). Alternatives: Natalia's phone run A01–A08, SM-A336M, Android 16, Render (**REAL-TEAM**) | BQ8: `BUDGET_PRIORITY` 25 of 51 recommendations selected (49.0 %); `CONTEXT_COMPATIBILITY` 2 of 13 (15.4 %); no other explanation type observed. Alternatives: with 60 min, COP 30,000 and vegetarian, the context is kept across categories, offline shows only a matching saved result, and reconnection refreshes it. **Every observed response was empty** ("no compatible alternatives", late at night) | BQ8 selections were made by team accounts testing the app, so they are **not a preference signal**; a selection is an in-app choice, not a purchase. **A positive alternative has not been observed on a device** (Natalia §6; backend unit tests cover it), so the app is not yet shown to find a compatible substitute |
| C3 | **Use groceries before they expire** | Inventory groups *Expired / Due today / Next 3 days / Later* (S3); BQ3 orders matches by the earliest expiry of a required ingredient ([SPRINT3_BQ3.md](SPRINT3_BQ3.md) rule 10); Cook lists `expiringIngredients`; inventory usage event (S4-01); faster BQ3 (S4-05) | BQ2, BQ3, BQ6 | BQ3 profiling: production catalog (6 recipes) and analyst inventory of 50 items (**REAL-TEAM**), plus 300 recipes / 500 items (**STRESS**) ([SPRINT4_MICROOPT_JP.md](SPRINT4_MICROOPT_JP.md)). BQ6 (includes **SEED**) | BQ3 output bit-for-bit identical after the optimization; stress evaluation **28.41 → 17.67 ms** (−37.8 %); real data 2.04 → 2.01 ms, no difference shown. Inventory event counted on the phone in all 8 scenarios ([SPRINT4_SCOPE.md](SPRINT4_SCOPE.md) §3.3). BQ6 November (partial month): `INVENTORY` used fewer than twice by **9 of 10** monthly active users, flagged. October: 8 of 8, because no inventory event existed before S4-01 | **No push reminders** (in-app warnings only). The app does not record what was eaten or thrown away, so **no claim of reduced food waste**. The BQ6 flag mixes seed and team accounts; it says low adoption among those accounts, not among students |
| C4 | **Restaurant confidence** through reviews and menu freshness | Structured reviews (overall, portion, taste, freshness, waiting time, value) with offline drafts and outbox delivery (S4-03, S4-04, [Juan Pablo](SPRINT4_JUAN_PABLO.md) §2); menu revisions and BQ13 partner health (S4-03) | BQ13 | Real 2026-10-27…11-25 (`includeSynthetic=false`, **REAL-TEAM**). Synthetic window A (**SEED**, phone check in Juan Pablo §5) | Real: 37 published reviews over 5 restaurants, means 3.29–4.50. **All 5 restaurants `UNKNOWN`**: no menu revision recorded yet. Both associations `INSUFFICIENT_DATA` (selection rate: 0 restaurants with published values; review mean: revisions constant). Seed window A: FRESH 64.3 % (9/14) vs STALE 27.3 % (3/11) selection, ρ = 0.89 (n = 5) | The 37 reviews are **30 demo-script reviews** (`npm run reviews:demo`, six demo accounts) **and 7 from team verification and device runs**; none from students. ρ = 0.89 comes from seed data and only shows that the computation works. Reviews do not prove a purchase. Correlation is observational, not causal |
| C5 | **Aggregated opportunities for restaurants** | BQ9 zone demand (S3, Natalia); BQ10 supply gaps (S4-06 backend, S4-07 *Supply gaps* analyst screen); BQ13 partner health (S4-03/S4-04) | BQ9, BQ10, BQ13 | BQ9 2026-10-01…11-25 (`containsSyntheticData: true`, **SEED** + **REAL-TEAM**). BQ10 `REAL` dataset 2026-10-27…11-26 (**REAL-TEAM**). BQ10 `SYNTHETIC` 2026-11-17 (**SEED**, 39 seeded searches), on Natalia's phone (B02–B10) and re-requested by Juan Pablo | BQ9: Centro 72 searches / 15 users; Chapinero 20 / 11; 18 cells suppressed below 5 users. BQ10 real: **every category `NO_OBSERVATIONS`** in the three zones; Centro has 5 mapped restaurants, Chapinero and Teusaquillo 0. BQ10 seed, Centro: 25 categorized searches, BOWLS 15 / 5 users (60 %), SOUPS and SUSHI 5 / 5 users (20 %), all three `GAP`; Chapinero SUSHI 5 / 5 users, `UNKNOWN_SUPPLY` (no mapped restaurant). The API re-request matches the phone's demand values | Searches and selections are interest indicators, not purchases. **The only BQ10 gaps come from seed data**; real categorized demand is still absent. Supply is a snapshot at request time, so available counts change by hour. The suppression threshold protects a single response only (backend limitation text) |
| C6 | **Reliable use with weak connectivity** (supports C1–C5) | Account-scoped outbox, sync center, retention, crash capture (S4-10); review outbox (S4-04); account-scoped BQ10 and alternatives caches with historical labels (S4-07) | BQ1 | Phone runs of S4-01/S4-04 and S4-07 (**REAL-TEAM**); BQ1 real window | Pending reviews and usage events survived airplane mode, a process kill and an account change, and were delivered once to the original account (Juan Pablo §5, Scope §3.3). Offline, BQ10 and alternatives show only an exact-match saved result labeled historical, never another zone's or category's (Natalia B06–B07, A04–A05). BQ1 recorded **1 fatal crash in 17 sessions** of `0.4.0-profile` on the SM-S948U1 (flagged, `INSUFFICIENT`) | That crash was real and is fixed (§3.1). Native crashes, ANRs and system kills are not captured |

### 3.1 Crash found through BQ1

The SM-S948U1 crash happened on 2026-11-25 13:22:52 Bogotá, during the S4-04 offline-review check, while the
synchronization center was open and the queued review was being delivered. The crash log of the phone says:

```text
java.lang.IllegalArgumentException: Key "3a624e58-…" was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.
```

Probable cause, from the source: `SynchronizationCenterViewModel` combines `observeOpen` and `observeSent`, two
independent Room queries. When an event changes from pending to sent, one emission can contain it in both lists,
and `SynchronizationCenterScreen` gives both rows the same key (`clientEventId`). The app reopened normally and the
review was delivered once. **Fixed** after this analysis: both lists now come from one Room query, so an event is in
exactly one of them in every emission ([SPRINT4_DANIEL.md](SPRINT4_DANIEL.md) §5.2).

## 4. Initial promise versus final behavior

| Sprint 1 promise | Final behavior (Android `fc7a442`, backend `1910b13`) | State |
|---|---|---|
| Register groceries manually or by receipt, barcode or photo | Manual entry only, with offline drafts and reconciliation of unknown outcomes | Scanning **not implemented**. BQ7 (registration methods) was not built |
| Timely reminders before food spoils | In-app expiration groups and Home warning list | Push notifications **not implemented**. BQ11 was not built |
| Quick meals from available ingredients and time | BQ3 recipe matching with diet and maximum time, expiry ordering, offline evaluation | Implemented. The catalog is small: 6 production recipes |
| Restaurant guide with menus, prices, hours, diet, portions | Search and detail with opening status, budget and diet filters, meals and prices, Valhalla walking times, offline cache | Implemented with 5 demo restaurants. Restaurants cannot edit their own data |
| Cook / Walk / Order comparison with time, cost, expiry and quality | Backend comparison with estimated minutes and cost, expiring ingredients for Cook, explanation, history and follow-up | Implemented with **estimated** costs and times. No order or payment is placed; "Order" uses fixed catalog delivery values |
| Structured post-meal evaluation | Review form with the five Sprint 1 aspects plus overall score; summary on the restaurant detail | **New in Sprint 4.** No proof of purchase. No moderation tools |
| Recommendations improve with reviews and previous choices | Reviews are shown and analyzed (BQ13) but do not change the ranking. The Walk choice breaks ties with the catalog's fixed `averageRating`, not with student reviews (`recommendations.service.ts`, `isBetterWalk`) | **Not implemented** in the ranking |
| Aggregated demand for partners | Analyst reports BQ6, BQ9, BQ10, BQ13 and BQ1 in the app; BQ8 through the backend API only | Available to the internal `ANALYST` role only. **No partner accounts or partner portal** |
| Paid partner services, promotions | None | **Not implemented**: no billing, subscription, sponsored content or BQ14 |

## 5. Revenue model: what the implementation supports

The freemium B2B model of Sprint 1 §1.3 is kept as the business proposal. It is **not validated**: no restaurant
was interviewed about prices, nothing is sold and no revenue exists.

| Revenue stream (Sprint 1) | Paying customer | Implemented basis | Missing before it could be sold |
|---|---|---|---|
| Free student core | — | Every student flow in §4 is free and needs only an account | Real student adoption (C3 shows low use among test accounts) |
| Restaurant partner subscription | Restaurants near campus | BQ13 partner health (freshness, selection rate, review scores) and the structured reviews it reads | Partner role and accounts, menu self-service (menu revisions are recorded by the backend but there is no partner editor), billing, price validation with restaurants |
| Sponsored promotions | Restaurants | Nothing. The recommendation ranking has no paid input, which keeps the Sprint 1 rule that payments cannot override budget, diet, time or expiry | Promotion model, "Sponsored" labeling, BQ14 measurement, billing |
| Aggregated analytics | Restaurants or food-service groups | BQ9 zone demand and BQ10 supply gaps with coarse zones, suppression below 5 users and explicit limitations | External access control, contracts, a larger and real data base; current reports mix seed data or have no observations |
| Institutional partnerships | Universities | BQ6 adoption and the anonymized aggregates above could support a campus report | Food-waste outcome data (not collected), institutional agreement |

## 6. Validation performed (functional, not a pilot)

| Run | Who and where | Sample | What it shows |
|---|---|---|---|
| S4-01 inventory usage event, 8 scenarios | Juan Pablo, Samsung SM-S948U1, Android 17, `profile` build of `c141c3c`, Render | 2 accounts, 6 server rows | Event counted once per visit, online, offline and across restart and account change |
| S4-04 reviews and BQ13, 6 scenarios (5 done, upgrade blocked by the release key) | Juan Pablo, same phone and build | 2 accounts, 2 real reviews | Offline draft and submission, single delivery, no cross-account delivery, BQ13 windows A/B, offline reopen |
| S4-04 emulator E1–E15 | Juan Pablo, AVD API 36, Render | — | Upgrade v9 → v10 and every BQ13 state |
| S4-05 BQ3 profiling | Juan Pablo, same phone, protocol S4-02 | 10 + 5 runs per variant | Performance and equivalence (C3) |
| S4-10 sync center, telemetry, BQ1 | Daniel, emulator API 37, local backend; physical rows run by Juan Pablo on the SM-S948U1, Render (Daniel has no Android phone) | 2 accounts | Daniel §5 and §5.1: injected crash delivered once, never under another account, lost acknowledgements resent without new server rows, double retry sent once, BQ1 fixtures. Upgrade from 0.4.0 blocked by the release key |
| S4-11 UTC formatter profiling | Daniel, emulator API 37 (the owner has no Android phone), protocol S4-02 ([SPRINT4_MICROOPT_DANIEL.md](SPRINT4_MICROOPT_DANIEL.md)) | 10 runs per variant and dataset | Timestamp formatting −84 % / −89 %, byte-identical payloads; no difference shown for the whole event admission (C6) |
| S4-07 alternatives and BQ10 report, 21 scenarios | Natalia, Samsung SM-A336M, Android 16, debug build of `f7ace38`, Render | 1 analyst and 1 student account; 3 zones; 3 categories | Navigation, seed gaps, suppression, unknown supply, offline hit and miss, reconnection, filter persistence, account isolation. Positive alternative, TTL expiry, in-flight race, mobile 403 and v11 → v12 upgrade not shown (Natalia §6) |
| BQ10 API cross-check | Juan Pablo, read-only `GET`s, Render | 3 requests | Phone demand values reproduced (§1, `render-2026-11-26/`) |

A pilot needs consenting students and a short script: start and end time of a meal decision, completion or
abandonment, chosen alternative, whether it fit their budget and time, and their explanation. With 5 to 10
participants it would describe those participants only; no rate could be generalized to the student population.

## 7. Attributable evidence per member

| Member | Evidence in this document | State |
|---|---|---|
| Juan Pablo | C3, C4, C6, S4-01/S4-04/S4-05 runs, report snapshots, consolidation | Done |
| Daniel | C1 and C6: comparison telemetry, sync center, crash capture, BQ1 data, S4-11 admission profiling | Supplied. Emulator evidence (Daniel §5); physical rows executed on the SM-S948U1 (Daniel §5.1); crash of §3.1 fixed (Daniel §5.2) |
| Natalia | C2 alternatives, C5 BQ10 backend and analyst report, C6 BQ10 caches | Supplied (PRs CampusMealBack #28/#29, Sprint-2-Kotlin #61/#62). Positive alternative on a device pending (Natalia §6); real BQ10 dataset has no observations |

## 8. How the reviewer reproduces representative claims

1. **BQ3 equivalence (C3):** `./gradlew :app:testDebugUnitTest --tests '*Bq3EquivalenceTest*'` passes against the
   golden files written before the optimization.
2. **BQ3 timing (C3):** `node scripts/profiling/summary.js` on
   `docs/evidence/sprint4/s4-05-bq3/{before,after}/{representative,stress}/sections.csv` gives the medians of §3.
3. **Reports (C2, C4, C5, C6):** as `ANALYST`, request the URLs listed in the evidence README and compare with the
   stored files. Later events may change counts; the labels (`includesSyntheticData`, `NO_OBSERVATIONS`,
   `UNKNOWN`, `INSUFFICIENT`) should not change without new data.
4. **Reviews (C4):** open any restaurant detail with any account; the *Student reviews* card shows the server
   summary. Its counts should match `bq13-real-30d.json`, plus reviews written later.
5. **BQ10 (C5):** as `ANALYST`, Profile → Analytics → BQ10, zone Centro, 2026-11-17 to 2026-11-17, synthetic
   demand: BOWLS, SOUPS and SUSHI with the demand values of `render-2026-11-26/` (supply depends on the hour).

## 9. S4-12 acceptance

- [x] Arguments cite implemented source, PRs, BQ outputs and observed data rather than future claims (§3).
- [x] Real, synthetic and stress data, and partial windows, are labeled (§1; partial November in C3).
- [x] Every unsupported original promise is disclosed and scoped (§4).
- [x] Billing, partner self-service and revenue are not claimed (§5).
- [x] All three members supply attributable evidence (§7). Each owner's own open items stay recorded in their
  documents and in §3.
- [ ] Reviewer reproduces representative claims (§8).
