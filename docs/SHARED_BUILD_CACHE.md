# Shared GCP build cache

Trusted Linux amd64 correctness CI can use the GCP runner pool and 128 GiB Spot
remote executors. Workers have a local file cache, then the shared Buildbarn NVMe
CAS, then authoritative BuildBuddy storage. Scheduler, build events and remote
execution remain in BuildBuddy; object compression remains enabled.

Repository variables control rollout:

- `BUILD_CACHE_ENABLED=true` enables the integration.
- `RUNNER_DEFAULT` selects the GCP runner label (operations currently uses
  `linux-amd64-cipool-xl`).
- `BUILD_CACHE_ENDPOINT` is the private HTTP gateway origin supplied by cloud ops.

CI calls the full-SHA-pinned `elide-dev/.github/actions/setup-build-cache` action.
Only successful network and GitHub OIDC identity verification exports
`ELIDE_BUILD_CACHE_ENABLED=true`; credential setup then selects
`--config=shared_cache`. That profile requests OCI isolation and pool
`bb-native-128`, inheriting the existing pinned image and toolchains. The action
also configures Gradle HTTP and sccache WebDAV for jobs that use those clients;
these protocols share the same CAS and backing store, with repository/ref-isolated
indexes. It does not install either client or change the pinned JDK/toolchains.

Fork PRs retain hosted local execution without BuildBuddy secrets. ARM and
explicit local/benchmark profiles retain their existing execution policy. No
organization-wide BuildBuddy default is changed. If setup fails, trusted jobs use
the existing hosted BuildBuddy profile. Clearing `BUILD_CACHE_ENABLED` rolls back
new jobs; no running job needs interruption. Local users can explicitly select
`--config=shared_cache` with their existing BuildBuddy credentials once enabled.

The cloud deployment and shared action must be available before enabling these
variables. Deployment and telemetry are managed in the cloud infrastructure repo.
