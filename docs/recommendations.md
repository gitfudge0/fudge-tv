# App recommendations

Platform references: [Android TV channels](https://developer.android.com/training/tv/discovery/recommendations-channel), [AOSP provider permissions](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml), and [AOSP query filtering](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/src/com/android/providers/tv/TvProvider.java).

The existing launcher displays preview channels published through Android's TV Provider and a separate Continue Watching queue from Watch Next. It does not supply Google TV's personalized Discover feed. Installed apps must publish records and the provider must permit this launcher's UID to read them; installing an app does not guarantee a recommendation row.

## Architecture

`Recommendations` is the replaceable provider source; `Library` assembles installed apps and the recommendation snapshot. Native code queries `TvContract` channels, preview programs and Watch Next off the UI thread. The existing Java/WebView bridge sends sanitized display metadata and opaque item IDs. Content launches remain native, validate the originating package and link, and fall back to the installed app when a link fails. The existing UI renders horizontal, D-pad navigable channel rows with lazy artwork and stable focus keys. Watch Next stays separate, and playback progress appears only for valid duration and position values.

`MainActivity` observes channels, preview programs and Watch Next while resumed. Provider notifications are debounced by 400 ms; package changes and foreground entry also refresh the snapshot. Lifecycle cleanup removes observers and pending refreshes, and cancels obsolete query work. Provider availability and query failures are reported independently so a restricted source does not disable the launcher.

Settings are under **Settings → TV controls & data access → Recommendations**. Preferences persist locally: recommendations globally, Continue Watching, maximum items per row (10, 20, 30 or 40), visible discovered channels, and row ordering through move-to-top. Hidden discovered channels remain available in settings. These controls filter accessible records; they cannot grant provider access.

## Permission boundaries

AOSP's deprecated `com.android.providers.tv.permission.READ_EPG_DATA` is a normal permission and is not the gate for unrestricted TV Provider access. Declaring it does not grant cross-app recommendations. `ACCESS_ALL_EPG_DATA` is signature/privileged: an ordinary installed launcher cannot obtain it by declaring it. `READ_TV_LISTINGS` can broaden access to searchable rows in AOSP, but OEM implementations and dataset-specific checks differ. Approval of TV listings access must not be interpreted as proof that preview channels or Watch Next from another app are visible.

Becoming the default Home application alone grants no broader provider access. AndroidX TV Provider helpers use the same underlying ContentResolver permission checks; adding them would not bypass those checks. Android TV, Google TV, AOSP builds and custom firmware can expose different subsets of records. A successful empty query means there were no visible records, not proof of unrestricted access. Provider absence, denial or failure omits unsupported rows without fabricating content. No root, private API, scraping or permission bypass is used.

## Real-device verification

On 2026-10-25, the signed release and instrumentation pair was tested on Sony BRAVIA 4K VH21, Android API 31, user 0, with Fudge already the default Home and READ_TV_LISTINGS granted. All nine instrumentation tests passed. The actual target-UID probe reported preview channels `empty` (zero rows) and Watch Next `available` (two items under the test’s temporary maximum of two); original preferences were restored. A normal foreground refresh then reported 18 Continue Watching items. This verifies accessible Watch Next records on this firmware, not unrestricted preview access. The final installed candidate passed a second instrumentation run: nine tests, 2.604 seconds. The home screen showing 18 Continue Watching items and the TV controls menu containing Recommendations were inspected. The native recommendation settings panel, actual content launching and publisher-driven observer notifications were not live verified; those still require device checks. Synthetic provider tests cover failure handling and mapping, and lifecycle unit tests cover observer registration callbacks, cancellation and debouncing. Google TV and AOSP devices remain unverified. Local fake-source tests establish implementation behavior, not third-party accessibility on other firmware. Test each device with an app known to publish TV Provider content, and record model, firmware/API version, Home role, effective permissions, capability state and returned counts.

| Device/setup | Evidence to collect |
| --- | --- |
| Standard Android TV | Launcher-UID channel and Watch Next queries; published item launches; observer refresh after publisher updates. |
| Google TV | Same checks; distinguish accessible TV Provider rows from the unsupported personalized Discover feed. |
| AOSP-based TV | Provider presence and effective app permissions; test both empty and publisher-populated datasets where available. |
| Default Home launcher | Repeat the same checks after selecting Fudge as Home through system settings; compare actual results, without assuming additional access. |

Useful commands (replace the serial when multiple devices are connected):

```sh
adb devices
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell dumpsys package local.tv.launcher
adb shell cmd role get-role-holders android.app.role.HOME
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p local.tv.launcher
adb logcat -d -s FudgeRecommendations:I
```

The package dump shows requested and granted permissions; inspect it locally rather than sharing unrelated device information. The role command may be unavailable on some builds. `FudgeRecommendations` logs capability states and row/Continue Watching counts from queries performed inside the launcher process. It intentionally does not log titles or deep links. A shell `content query` runs under the shell UID and is **not evidence** that the launcher has equivalent access. Do not use protected-permission grants as a workaround.

For each supported dataset, exercise horizontal and vertical D-pad navigation, focus restoration after refresh/removal, missing artwork, portrait artwork, RTL, and long catalogs. Play a recommendation and confirm its originating app opens the intended content. Test a broken link and app removal; stale entries must disappear and launch failures must remain recoverable. Publish/update/remove records while the launcher is foregrounded, then repeat with the launcher paused and resumed. Toggle visibility, ordering, global and Continue Watching settings and verify persistence after restart. Denied/unavailable providers must leave installed-app launching functional.

Record outcomes separately: **implemented and locally verified**, **device verification required**, **restricted by this firmware's permissions**, and **unsupported through public APIs**. A restricted device is a valid graceful-fallback result, not successful cross-app recommendation access.

Implementation limits: discover at most 64 channel records, show at most 12 populated visible rows, and retain at most 40 items per row. Each provider cursor scan examines at most 5,000 records, so exceptionally large catalogs may be truncated. The WebView uses bounded row DOM and lazy artwork instead of native RecyclerView recycling. Artwork uses an access-ordered 1,024-entry registry, an 8 MiB byte cache and a 16 MiB disk cache.

Automated Android tests use an injected source and platform cursors for mapping, malformed records, failures, sorting, deduplication, bounds and safe links. The separate actual-provider probe queries through the target application context and reports only capability states/counts. Build the signed release pair with `:app:assembleRelease :app:assembleReleaseAndroidTest`; the test runner is `local.tv.launcher.test/androidx.test.runner.AndroidJUnitRunner`. Running instrumentation temporarily exercises recommendation preferences and restores their original values afterward. Fake-source tests are not evidence that another app’s records are accessible.
