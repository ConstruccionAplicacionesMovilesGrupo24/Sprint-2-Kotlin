# S4-11 measurement conditions (both variants)

| Field | Before | After |
|---|---|---|
| Git | `dad0a47` (burst tool, no optimization) | `cd52361` (only `OutboxRepository.kt` + `UtcTimestamps.kt` differ in `app/src/main`) |
| Profile APK SHA-256 | `72e442e42f20d52c7b205ef691e1b0ce1cc7c35bad238c4407a40f9f0497068d` | `c7318c15585e9c34058aab47a96cfa1d2067b9aa1c3e235ba5b1642f27ce3b2b` |
| Date | 2026-11-25 | same |
| Device | Android Emulator 37.2.12.0, AVD `Medium_Phone_API_37.0` (`sdk_gphone64_arm64`, Android 17, API 37), 4 vCPU, 4096 MB | same AVD, same boot |
| Host | MacBook Pro, Apple M4 Pro, 24 GB, macOS, on power | same |
| Why an emulator | The owner has no Android phone (protocol §1.1 and §3, condition 1) | same |
| Compilation | `adb shell cmd package compile -m speed -f com.campusmeal.android.profile` after install | same |
| Start condition | 1-minute load average of the emulator below 2.0 (1.73) | same (1.97) |
| Load at the end of the 20 runs | 6.67 / 4.22 / 1.92 | 8.43 / 5.67 / 3.42 |
| Workload | `scripts/profiling/s4-11-burst.sh <dir> representative 10`, then `stress 10` | same |
| Per run | Perfetto `campusmeal.pbtxt` (unchanged), fresh process (`am start -S`), 50-event warmup on its own database, then the traced burst | same |
| Dataset | Disposable on-disk Room database and DataStore per run, synthetic account, no backend, no network; ids and instants fixed by event index | same |
| Events | Representative 20 (5 comparisons × usage, impression, selection, load), stress 500, one caller | same |
| Raw files | `before/{representative,stress}/` | `after/{representative,stress}/` |

Each folder contains the 10 traces (`run-NN.perfetto-trace`), `burst-log.txt` (result line of every run, with the payload
SHA-256), `sections-all.csv` (every `cm.*` section), `sections.csv` (only `cm.outbox.*` inside the burst window),
`totals.csv` (per-run sums) and `summary.txt` (median and range). `check.txt` is the concurrent-callers check.

Discarded: a first "before" capture on the same AVD configured with 1 vCPU and 2 GB (load average 12–14 during the runs,
per-run `isoUtc` medians 0.011–0.206 ms, representative bursts 4–1 858 ms). It was replaced before any "after" capture
was taken, and the AVD was set to 4 vCPU and 4 GB for both variants. Its traces were not kept in the repository.

Both APKs were built from the working tree at the stated commit plus two local, comment-only edits that were never
committed (`OutboxScheduler.kt`, `AnalystReport.kt`). They change no code, only line numbers in those two classes, and
were present in both variants; a clean checkout therefore produces a different APK hash with the same code.
