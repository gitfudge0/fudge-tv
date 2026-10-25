# Production native rendering benchmark

This framework-only instrumentation APK targets the **actual installed production APK**. It imports no app classes, AndroidX, JUnit, or Kotlin and needs no production R8 keep rules or fixture hooks.

From the repository root, after the operator installs the production APK on the task-local emulator:

```sh
python3 tools/benchmark-native-release.py \
  --serial emulator-5560 \
  --apk app/build/outputs/apk/release/app-release.apk
```

The driver requires an explicit `emulator-NNNN` transport and `ro.kernel.qemu=1`. It checks that the installed target APK SHA-256 exactly matches `--apk`, builds/signs the helper with the existing `launcher-signing.p12`, checks matching signing certificates, then installs and runs only the helper. It never installs the target APK, clears app data, seeds caches, changes preferences, or selects a Home app.

Build without any ADB calls:

```sh
python3 tools/benchmark-native-release.py \
  --serial emulator-5560 \
  --apk app/build/outputs/apk/release/app-release.apk \
  --build-only
```

Requires Android SDK platform/build-tools 35, a JDK supporting Java 8 compilation, and the existing development signing key. `--sdk-root`, `--build-tools`, and `--signing-key` override their defaults. Alternate key passwords use `FUDGE_BENCHMARK_STORE_PASSWORD` and `FUDGE_BENCHMARK_KEY_PASSWORD`.

## Frozen workload

The runner connects its framework UiAutomation accessibility probe before creating the production Activity, because RecyclerView binds public item delegates only while accessibility is enabled. This ephemeral instrumentation service and its enabled state are recorded in the result context; no persistent accessibility preference is changed. The runner opens the production Activity and navigates Down to **Discover with Cinemeta**, waiting at most 30 seconds. Public accessibility collection metadata must expose 24 cards. It stabilizes at position zero, then performs one warmup and three measured samples. Each sample sends 60 real D-pad events: ten Right, ten Left, repeated three times, paced at nominal 80 ms intervals, followed by a 250 ms animation tail.

Before the workload, the runner records effective `animatorsEnabled` and the global `animatorDurationScale` (default 1 when unset) on the main thread alongside display metadata. It reports `blocked` without running the workload if effective animators are disabled; the configured scale alone does not establish that animations run.

The first forward warmup pass records eleven unique focus descriptions. Every later movement must match both the expected accessibility column and the frozen forward/reverse description sequence. Edge clamps, changed content, lost window focus, and incomplete work fail the run. No activation/launch keys are sent.

A dedicated frame-metrics thread records total duration, layout/measure duration, unknown delay, draw duration, synchronization duration, GPU duration, frame deadlines, deadline misses, excluded first-draw frames, and dropped metric reports. Each sample reports median, p95, maximum, frame count, completed work, and actual event cadence. No arbitrary smoothness threshold is asserted.

The additional distributions are `unknownDelayDurationMs`, `drawDurationMs`, `syncDurationMs`, and `gpuDurationMs`. Each includes its own supported-value `count`; negative/unsupported values are excluded, and an unsupported metric reports count zero with null statistics. These cover the same usable non-first-draw frames as the existing metrics. They help separate layout/draw work from scheduling, synchronization, or GPU delays, but their stages can overlap, so their values should not be summed to reconstruct total duration.

## Results and limits

Artifacts are written under `.local/native-verification/release-benchmark/`:

- `build.json`: production/helper hashes and version.
- `instrumentation.txt`: raw instrumentation output.
- `result.json`: parsed result, samples, frozen identities, and device/display/renderer context.

The driver accepts success only from valid structured instrumentation JSON with all 180 measured movements and 60 warmup movements completed and usable frames captured. A shell exit code of zero alone is insufficient. Parser regression checks run with:

```sh
python3 tools/native-release-benchmark/test_driver.py
```

Catalog data and artwork come from the production app's existing one-hour cache and normal network behavior; the helper does not control or inspect private cache contents. Missing catalog data or insufficient public identity metadata is reported as blocked. Changes to the observed sequence fail rather than produce a faster result. Dropped metric reports remain visible in the output and limit coverage.

Results describe this production APK on the recorded emulator, including any SwiftShader renderer hint and instrumentation overhead. They establish neither physical-TV smoothness nor a comparison with the former WebView pipeline. Repeat runs must use the same APK, device configuration, catalog identities, and workload to be comparable.
