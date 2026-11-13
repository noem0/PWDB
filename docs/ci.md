# CI build environment

The workflow uses the Keiyoushi source build system from a sparse checkout. The root Gradle wrapper and settings files are explicitly materialized from the upstream commit because cone-mode sparse checkout may omit root-level wrapper files. Android 17's SDK platform package is `platforms;android-37.0`.
