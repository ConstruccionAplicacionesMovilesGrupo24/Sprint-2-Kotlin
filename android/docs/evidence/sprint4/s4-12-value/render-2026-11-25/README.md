# S4-12 — analyst report snapshots (Render + Neon)

Read-only `GET` requests to `https://campusmeal-api.onrender.com/api/v1/analytics/…` as the `ANALYST` demo account,
on 2026-11-25 21:05 America/Bogota (`generatedAt` 2026-11-26T02:05Z). Backend `main` `cd1947e`. Nothing was
written. The responses are stored unchanged, only pretty-printed. They contain no tokens, emails or coordinates.

| File | Request | Dataset as reported by the backend |
|---|---|---|
| `bq6-2026-10.json` | `feature-adoption?month=2026-10` | `includesSyntheticData: true` (Sprint 3 usage seed) |
| `bq6-2026-11.json` | `feature-adoption?month=2026-11` | `includesSyntheticData: true`, `partialMonth: true` |
| `bq8-oct-nov.json` | `explanation-selection?from=2026-10-01&to=2026-11-25` | No synthetic flag; no seed writes typed explanations (the BQ13 seed uses `explanationType: null`, excluded by the query). Rows come from team and test accounts |
| `bq9-oct-nov.json` | `zone-demand?from=2026-10-01&to=2026-11-25` | `containsSyntheticData: true` |
| `bq10-{centro,chapinero,teusaquillo}.json` | `supply-gaps?zone=…&from=2026-10-27&to=2026-11-25` | `synthetic: false` (default `REAL` dataset) |
| `bq13-real-7d.json`, `bq13-real-30d.json` | `partner-health?from=2026-11-19&to=2026-11-25`, `…from=2026-10-27…` | `includesSyntheticData: false` |
| `bq1-real.json` | `performance?from=2026-11-01&to=2026-11-25` | `includesSyntheticData: false` |

To reproduce: the same requests with an `ANALYST` token. Later events change the numbers. The values in
`SPRINT4_VALUE_PROPOSITION.md` refer to these files.
