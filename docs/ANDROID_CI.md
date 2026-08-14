# Android CI verification

Pull requests and Google Play open-beta releases run
`ci_scripts/verify_android.sh`. The gate assembles the debug Android app, runs
Android lint and local unit tests, and runs every KMP `jvmTest` suite. It then
runs the small Compose instrumentation suite on a disposable Pixel 6 emulator
using API 35. Gradle's plain console output names a failing task directly.

Both workflows use Java 21 and `gradle/actions/setup-gradle` to reuse wrapper,
dependency, and local build caches. A clean GitHub-hosted runner still works on
a cache miss; caching only shortens later runs. Expect roughly 3–5 minutes on a
cold runner, with warm-cache runs generally faster. The Play workflow repeats
this read-only, secret-free gate in a separate job before installing Infisical
or running `publishReleaseBundle` with injected release credentials.

## Manual accessibility verification

Instrumentation protects keyboard/switch activation and Rest mode at the input
and Compose semantics layers. Before a Play release that changes an access path,
verify these behaviors on a real device with the user's actual access setup:

- TalkBack announces actionable communication cells, controls, dialogs, and
  their selected/disabled state in a useful order.
- A paired keyboard or switch activates the focused target exactly once, and
  Rest mode stops and resumes activation without trapping focus.
- Touch-and-hold timing, large touch targets, and visible focus/activation
  feedback remain usable with the configured accessibility timing.
- Speaking, board navigation, and backup restore preserve the composed message
  and current communication context after interruptions and configuration
  changes.

Use a dedicated test device for instrumentation. Never run
`connectedDebugAndroidTest` on a user's Wingmate device: runner cleanup and
debug signing can replace or remove the installed app and its private data.
