# S4-12 — BQ10 cross-check of Natalia's device evidence (Render + Neon)

Read-only `GET` requests as the `ANALYST` demo account on 2026-11-26 10:58 America/Bogota (`asOf`
2026-11-26T15:58Z), backend `main` `1910b13`. Nothing was written. Responses stored unchanged, pretty-printed.

| File | Request | Compared with |
|---|---|---|
| `bq10-centro-synthetic-2026-11-17.json` | `supply-gaps?zone=bogota-centro&from=2026-11-17&to=2026-11-17&dataset=SYNTHETIC` | `SPRINT4_NATALIA.md` B02 |
| `bq10-chapinero-synthetic-2026-11-17.json` | `supply-gaps?zone=bogota-chapinero&from=2026-11-17&to=2026-11-17&dataset=SYNTHETIC` | `SPRINT4_NATALIA.md` B04 |
| `bq10-centro-real-2026-10-27-11-26.json` | `supply-gaps?zone=bogota-centro&from=2026-10-27&to=2026-11-26` (default `REAL`) | Real dataset, §3 C5 |

Demand values match the phone screenshots: Centro 25 categorized searches, BOWLS 15 / 5 users / 60 %, SOUPS and
SUSHI 5 / 5 users / 20 % each, all three `GAP`; Chapinero SUSHI 5 / 5 users / 100 %, 0 mapped restaurants,
`UNKNOWN_SUPPLY`. Supply is computed at `asOf`, so the available counts differ from the late-night phone run
(BOWLS 1 available here, still within the low-supply limit of ≤ 1).
