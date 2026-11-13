# Gradle wrapper in CI

The sparse CI checkout explicitly restores the upstream wrapper script, wrapper JAR, and wrapper properties from the checked-out upstream commit before invoking Gradle.
