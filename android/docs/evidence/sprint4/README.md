# Sprint 4 profiling evidence

One folder per pair, with the original files: `s4-05-bq3/`, `s4-08-restaurant-cache/`, `s4-11-iso-utc/`.

Each folder has `before/` and `after/` with:

| File | Content |
|---|---|
| `run-NN.perfetto-trace` | Original system trace (or a link, if it is too large for the repository) |
| `sections.csv` | Every raw row of `scripts/profiling/sections.sql`, with the run number |
| `conditions.md` | The table of §4 of `SPRINT4_PROFILING_PROTOCOL.md`, filled in |
| `allocations.*` | Only if the separate debuggable allocation pair was recorded |

Nothing here is written by hand or estimated: a value without its original trace is not evidence.
