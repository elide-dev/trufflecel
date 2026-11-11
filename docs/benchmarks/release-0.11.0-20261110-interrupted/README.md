# Interrupted TruffleCEL 0.11.0 confirmation round

This attempt started on unclemax at 2026-11-10 21:19:56 UTC after 45 seconds
without detected competing compute work. The five-second process guard stopped
our entire round at 21:39:53 UTC when an external SSH session exceeded its CPU
threshold. Subsequent read-only process inspection observed `llvm-profgen` and
then `perf2bolt` in another session. No other user's processes were stopped.

All 144 primary memory processes and 24 JVM timing cells (72 forks) completed.
Native timing stopped after 18 of 72 processes. Prepared-input and ingestion
phases did not run. This incomplete attempt is not used for headline charts or
pooled with another round. Its results have not passed complete-round validation.

[raw.tar.gz](raw.tar.gz) preserves every completed sample, partial phase metadata,
source identity, driver copies, run script/log, exit status, guard script and
monitor observations. The unchanged release source archive and artifact build
receipt are retained in the [first attempt](../release-0.11.0-20261110-shared/README.md).
The [checksums](SHA256SUMS) cover these records.

## Second interrupted attempt

[retry-raw.tar.gz](retry-raw.tar.gz) retains a full restart that began at
21:45:14 UTC after three quiet minutes. It stopped at 21:54:10 UTC when another
session launched `javac` at 100% CPU. Follow-up process inspection observed `wrk`
at about 1,189% CPU in that session. All 144 memory processes and eight JVM timing
cells (24 forks) completed; later phases did not run. This attempt is also
excluded from headline charts, with its own original source manifest and guard
observations retained. No samples are pooled across attempts.

## Third interrupted attempt

[third-raw.tar.gz](third-raw.tar.gz) retains a restart that waited for another
session's `native-image` build to exit, then began at 22:32:43 UTC after 180 quiet
seconds. At 22:46:15 UTC the active timing driver received SIGTERM and the round
exited with status 143. The guard recorded no competing work in any of its 162
measurement-stage observations, and it did not send that signal. The sender is
not established. All 144 memory processes and 16 JVM timing cells (48 forks)
completed; later phases did not run. This attempt is excluded from headline
charts, and no samples are pooled across attempts.
