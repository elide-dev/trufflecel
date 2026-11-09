# Exploratory heap controls

These are single-fork, materialized-comprehension controls used to select the next
qualification matrix. They are **not** publication-quality throughput comparisons.
All four engines and all four requested profiles are retained, including the
unpromoted `shared-free-32m` setting. Both engines receive the same flags in each
profile, on the same combined release executables with 512 MiB initial/maximum heaps.

Current RSS and peak RSS come from fixed-work processes; time comes from separate
processes. The raw rows, source/driver/artifact identities, commands and load samples
are in the adjacent JSON files. The driver was `3310409`.

The TruffleCEL guest-JIT row with `shared-free-32m` measured about 2,035 ns/op versus
1,882 ns/op for default, while the 32 MiB young-generation row measured about 1,789
ns/op. Those single observations motivated broader testing of young-generation
bounds. They do not establish significance, generality or a universal optimum.
