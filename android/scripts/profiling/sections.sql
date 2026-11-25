-- Run in https://ui.perfetto.dev -> Query (SQL). Raw duration of every CampusMeal section, in order.
-- Copy all rows to the evidence CSV: median and range are computed from these raw values.
SELECT s.name, s.ts, s.dur / 1e6 AS duration_ms
FROM slice s
WHERE s.name GLOB 'cm.*' AND s.dur >= 0
ORDER BY s.name, s.ts;
