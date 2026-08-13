# Android CI verification

Pull requests and Google Play open-beta releases run
`ci_scripts/verify_android.sh`. The gate assembles the debug Android app, runs
Android lint and local unit tests, and runs every KMP `jvmTest` suite. Gradle's
plain console output names a failing task directly.

Both workflows use Java 21 and `gradle/actions/setup-gradle` to reuse wrapper,
dependency, and local build caches. A clean GitHub-hosted runner still works on
a cache miss; caching only shortens later runs. Expect roughly 3–5 minutes on a
cold runner, with warm-cache runs generally faster. The Play workflow repeats
this read-only, secret-free gate in a separate job before installing Infisical
or running `publishReleaseBundle` with injected release credentials.
