# Development and verification

## Build and verify

Install Java 21, Python 3, Android SDK platform 35 and build-tools 35.0.0. Set `ANDROID_SDK_ROOT` for a nondefault SDK location and `LAUNCHER_JAVA_HOME` for a specific JDK. Android Studio's bundled JDK is used when present on macOS. Node.js is not required.

```sh
./verify.sh
```

This checks Java formatting, runs the Python recovery-tool tests and JVM unit tests, assembles an optimized signed release, runs Android lint, verifies the APK signature, and prints its SHA-256. The output is `fudge-launcher.apk`; reports are under `app/build/reports/`. Release builds use R8, resource shrinking, Android's optimized rules, and AndroidX consumer rules. Android instrumentation is separate because it installs an app/test pair.

```sh
./format.sh --write                    # Apply the pinned Java formatter
./format.sh --check                    # Check without changing sources
./build.sh                            # Release, JVM tests, lint, APK signature
./build.sh :app:assembleVerificationAndroidTest
./verify-android.sh --serial emulator-5560
```

The final command builds and installs a `verification` app/test pair on an explicitly selected local emulator. This uses the production Java and signing configuration with shrinking and minification disabled for fixture testing. Fixture activities live only in the target verification app and are not exported; the test APK has its own normal instrumentation manifest. The normal `release` build keeps its full closed-world optimizations; fixture tests do not substitute for separately launching and inspecting that actual release APK. It leaves default Home and system packages unchanged. It does not target a connected TV. GitHub Actions runs the same device-free verification with Java 21 and SDK 35.

The scripts use pinned Gradle 8.7 and Google Java Format 1.24.0, downloading and checksum-verifying them into ignored `.tooling/` when needed. Gradle dependencies use explicit versions. Cinemeta is public and needs no API key, credential file, or account.

On the first build, an ignored development `launcher-signing.p12` is generated. Preserve an existing installation's private signing key: a different certificate cannot update that installation in place. The public development passwords in the build configuration protect no account credentials. Build products, signing keys, local emulator data, and operator backups remain outside Git.

## Native architecture

`MainActivity` owns lifecycle, asynchronous library/catalog work, permission requests, and validated app launches. `NativeHomeView` owns Home, Search, Settings, and title dialogs. `NativeRowAdapter` and `NativeCardAdapter` use RecyclerView/ListAdapter with stable content identity and asynchronous diffing, recycling horizontal rows/cards rather than rebuilding the entire screen. Inter is an Android font resource, with its license in the packaged assets.

Shared app recommendations and Continue Watching keep a bounded private on-device copy of real publisher records. Startup shows that copy after checking installed apps while provider updates run in the background. Temporary read failures keep prior content; successful provider removals replace it. Recommendation settings still apply immediately. Empty Continue Watching, recommendation, and Discover rails are hidden.


Installed apps and TV Provider records load away from the main thread. Provider changes are debounced and obsolete queries canceled. `LatestRequests` coalesces Home, Search, and Details work independently, cancels superseded requests, and prevents stale lifecycle completions from updating the view. `NativeImages` loads sampled bitmaps into ImageViews using shared, bounded background work, a memory budget, a bounded disk cache, and recycled-target checks.

`CatalogClient` independently fetches four home sources using a bounded source executor. Simultaneous lookups for the same key share one fetch. A cached Home envelope can display before a stale entry refreshes. Complete responses remain fresh for one hour; partial responses never overwrite complete cached results, and provider failure can return explicitly stale data. Catalog HTTPS responses are allowlisted and bounded to 1 MiB per list/5 MiB per detail. The transport checks cancellation, socket timeouts, and a shared elapsed-time budget across the permitted redirect.

## Data and controls

- Home shows Continue Watching at 30% of the initial screen, retained streaming apps in recent-use order, one contextual Recommendations row below Your apps, and a separate Discover row of portrait Cinemeta posters. Highlighting an installed app updates the rectangular recommendation thumbnails using only that app’s accessible preview channels; empty recommendations explain the missing shared content and D-pad navigation skips the empty rail. The selected app remains indicated while browsing its recommendations, Up returns to it, and each app retains its recommendation position. A smooth navy-to-purple gradient fades to deep navy behind the compact icon-only Home/Search/Settings capsule at the top left. Apps retain the original enlarged focus motion and captions below the banner; Discover uses the original portrait poster treatment. The final Inputs & controls rail uses compact icon-and-label tiles for real accessible TV input names; full port/state metadata remains in accessibility descriptions, and unavailable port discovery offers the system input menu. D-pad moves among native controls/cards; OK activates the selected action and Back closes a dialog or returns from Search.
- Continue Watching and provider rows use only records actually visible through Android's TV Provider. Published progress, episode labels, artwork, and descriptions retain their provider identity. Links must resolve to the retained owning app and an exported activity; unsupported links fall back to opening that app. There is no cross-provider metadata enrichment or fabricated playback/progress.
- Search filters installed TV apps and their shared recommendations locally and queries accessible Android searchable providers. Real provider results appear as landscape thumbnails under **From your apps**; the **Search in your apps** row uses the installed Home apps’ banners and order, opens supported query-bearing app searches, and labels unsupported apps **Search unavailable**. Stremio uses its documented search link; JioHotstar’s installed TV app ignores Android search queries, so its search shortcut remains unavailable. Provider searches have a 1.8-second deadline; app support and permissions determine available results. Cinemeta title search runs independently; edits/close/lifecycle changes discard obsolete results. Movie and series title details open exact registered Stremio links with autoplay disabled. Catalog matches do not establish subscription or streaming availability.
- Settings offers installed apps, refresh, Inputs/Live TV, Wi-Fi/system settings, optional TV listings/usage access, and recommendation visibility/order controls. Fudge requests default-channel initialization through Android’s public app receiver integration once per app update. Provider permissions and publisher support vary by device; an empty result does not prove unrestricted access.

Weather, geolocation, web presentation, analytics, and playback engines are absent. See [recommendation boundaries](recommendations.md) and [catalog contracts](cinemeta-migration.md).

## Install and recover

The physical-TV installer requires the exact serial, expected model, API, and user profile:

```sh
./build-and-install.sh --serial "$TV_SERIAL" --model "$TV_MODEL" --sdk "$TV_API" --user "$TV_USER"
```

It verifies the target and package/version, installs only for that profile, and cleans up its temporary APK. It does not connect ADB, choose default Home, disable packages, grant permissions, or change settings. Preview the app before choosing Home. Keep the original Home available; restore and verify it before uninstalling a deployed custom launcher. [Recovery tools](device-recovery.md) are separately opt-in.

## Verification scope

JVM tests cover catalog identity, malformed/partial results, cache bounds, concurrent fetch/coalescing, stale-first delivery, cancellation/recovery, request lanes, provider policy, and image-disk-cache behavior. Android tests cover actual bitmap/ImageView behavior, native UI/lifecycle flows, and synthetic TV Provider mapping. The live Cinemeta test requires explicit `liveCinemeta` opt-in. Test commands are repeatable verification recipes; their presence does not claim a particular checkout or device passed.

The frozen `CatalogLatencyBenchmark` measures cold Home completion with four fake sources delayed 40 ms each, two warm-ups, and nine samples. On one local JDK 25.0.3 run, the earlier sequential implementation had a 176.45 ms median (170.90–181.53 ms); concurrent fetching had a 46.41 ms median (44.27–46.68 ms), with all four sources and both result sections completed in every sample. This isolates source scheduling and does not establish real provider latency, TV frame rate, or native UI performance. Emulator and physical-TV checks must be reported separately from these measurements.

No project-wide license has been selected. Third-party code, fonts, metadata, and artwork retain their respective licenses and rights; see [third-party notices](../THIRD_PARTY_NOTICES.md).
