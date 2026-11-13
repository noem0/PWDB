# Upstream build dependencies

The CI job checks out the root Gradle build logic and the `common`, `compiler`, `core`, `lib`, and `lib-multisrc` projects from Keiyoushi. It explicitly checks out those paths before Gradle configuration.
