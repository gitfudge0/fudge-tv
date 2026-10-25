# App recommendations

Platform references: [Android TV channels](https://developer.android.com/training/tv/discovery/recommendations-channel), [AOSP provider permissions](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml), and [AOSP query filtering](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/src/com/android/providers/tv/TvProvider.java).

The native launcher displays one recommendation row per installed app, merging its preview channels published through Android's TV Provider and a separate Continue Watching queue from Watch Next. It does not supply Google TV's personalized Discover feed. Installed apps must publish records and the provider must permit this launcher's UID to read them; installing an app does not guarantee a recommendation row.

Browser recommendations from the Vewd publisher (`com.vewd.core.integration.dia`) are excluded from preview rows, Continue Watching, and publisher initialization. The browser remains available for app launching and search.

## Architecture

`Recommendations` is the replaceable provider source; `Library` assembles installed apps and the recommendation snapshot. Native code queries `TvContract` channels, preview programs and Watch Next off the UI thread. Native view models carry sanitized display metadata and opaque item IDs to RecyclerView adapters. Content launches validate the originating package and link, and fall back to the installed app when a link fails. Duplicate titles (normalized for case and spacing), content IDs, and validated content targets are removed within each app; content type and season/episode metadata keep distinct episodes separate. Apps retain their own launch targets, and the same title can appear under different apps. The row heading uses the installed app label. Native horizontal RecyclerViews render D-pad navigable app rows with shared bitmap loading and stable item identities. Watch Next stays separate, and playback progress appears only for valid duration and position values.

After discovering installed TV apps, Fudge sends Android's documented `ACTION_INITIALIZE_PROGRAMS` broadcast to each app's eligible receiver. Receivers must be exported, enabled, owned by that app, and accessible with this launcher's permissions. Each successfully dispatched receiver is recorded once per package version and update timestamp, so ordinary refreshes do not repeatedly initialize an unchanged app. Failed dispatches can retry on a later load. Disabling recommendations also disables these requests. An initialization request lets the app publish its default channel asynchronously; dispatch does not guarantee that it publishes records or makes them accessible. Apps without this public integration or accessible published programs cannot supply recommendation rows.

`MainActivity` observes channels, preview programs and Watch Next while resumed. Provider notifications are debounced by 400 ms; package changes and foreground entry also refresh the snapshot. Lifecycle cleanup removes observers and pending refreshes, and cancels obsolete query work. Provider availability and query failures are reported independently so a restricted source does not disable the launcher.

Settings are under **Settings → TV controls & data access → Recommendations**. Preferences persist locally: recommendations globally, Continue Watching, maximum items per row (10, 20, 30 or 40), visible discovered channels, and row ordering through move-to-top. Hidden discovered channels remain available in settings. Source-channel visibility is applied before app aggregation, and the highest ordered visible source determines its app row order. Item limits apply to the merged app row. Published preview channels can appear even when the system launcher has not selected their channel `browsable` flag; Fudge applies its own visibility preferences. Individual programs marked non-browsable remain excluded. These controls filter accessible records; they cannot grant provider access.

## Permission boundaries

AOSP's deprecated `com.android.providers.tv.permission.READ_EPG_DATA` is a normal permission and is not the gate for unrestricted TV Provider access. Declaring it does not grant cross-app recommendations. `ACCESS_ALL_EPG_DATA` is signature/privileged: an ordinary installed launcher cannot obtain it by declaring it. `READ_TV_LISTINGS` can broaden access to searchable rows in AOSP, but OEM implementations and dataset-specific checks differ. Approval of TV listings access must not be interpreted as proof that preview channels or Watch Next from another app are visible.

Becoming the default Home application alone grants no broader provider access. AndroidX TV Provider helpers use the same underlying ContentResolver permission checks; adding them would not bypass those checks. Android TV, Google TV, AOSP builds and custom firmware can expose different subsets of records. A successful empty query means there were no visible records, not proof of unrestricted access. Provider absence, denial or failure omits unsupported rows without fabricating content. No root, private API, scraping or permission bypass is used.

## Real-device verification

On October 25, 2026, the signed verification build on Sony BRAVIA at `192.168.1.6:5555` queried the TV Provider through the launcher app UID before and after normal publisher initialization. The post-initialization probe reported seven merged app rows and 18 separate Watch Next items. Accessible source-channel/item counts were Apple TV 64/20 (channel discovery capped), Vewd 1/3, Crunchyroll 1/20, Hotstar 1/10, Prime Video 1/20, Stremio 3/9, and YouTube 4/20. Google TV exposed three source channels with zero visible items. Netflix, Sony Pictures Core, and YouTube Music exposed no accessible source channels or items in this observation. All discovered source channels had the system-home browsable flag unset; Fudge's independent visibility policy allowed their accessible programs to appear.

These counts establish current app-UID access and aggregation on this device, within configured item and discovery limits. They do not prove that initialization created those records, that apps with no accessible records never publish, or that other firmware grants the same access. The full emulator suite passed 50 Android tests, including provider fixtures and native UI checks; those fixtures do not establish third-party access. Final release UI and physical remote acceptance on the TV remain separate checks.

| Device/setup | Evidence to collect |
| --- | --- |
| Standard Android TV | Launcher-UID channel and Watch Next queries; published item launches; observer refresh after publisher updates. |
| Google TV | Same checks; distinguish accessible TV Provider rows from the unsupported personalized Discover feed. |
| AOSP-based TV | Provider presence and effective app permissions; test both empty and publisher-populated datasets where available. |
| Default Home launcher | Repeat the same checks after selecting Fudge as Home through system settings; compare actual results, without assuming additional access. |

Device inspection commands (always select the intended serial):

```sh
adb devices
adb -s "$TV_SERIAL" shell getprop ro.build.version.release
adb -s "$TV_SERIAL" shell getprop ro.build.version.sdk
adb -s "$TV_SERIAL" shell getprop ro.build.fingerprint
adb -s "$TV_SERIAL" shell dumpsys package local.tv.launcher
adb -s "$TV_SERIAL" shell cmd role get-role-holders android.app.role.HOME
adb -s "$TV_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p local.tv.launcher
```

The package dump shows requested and granted permissions; inspect it locally rather than sharing unrelated device information. The role command may be unavailable on some builds. Inspect the native Continue Watching/provider-row status text and **Settings → TV controls & data access → Recommendations** for the current capability-dependent controls. For exact launcher-UID capability states and counts, run the explicitly selected verification instrumentation probe `RecommendationsTest.actualProviderCapabilityProbeDoesNotAssumeVisibility`. That test writes its capability/count summary to the test-only `FudgeRecommendationProbe` tag; collect it afterward with `adb -s "$TV_SERIAL" logcat -d -s FudgeRecommendationProbe:I`. The normal native app has no dedicated recommendation logcat tag. A shell `content query` runs under the shell UID and is **not evidence** that the launcher has equivalent access. Do not use protected-permission grants as a workaround.

For each supported dataset, exercise horizontal and vertical D-pad navigation, focus restoration after refresh/removal, missing artwork, portrait artwork, RTL, and long catalogs. Play a recommendation and confirm its originating app opens the intended content. Test a broken link and app removal; stale entries must disappear and launch failures must remain recoverable. Publish/update/remove records while the launcher is foregrounded, then repeat with the launcher paused and resumed. Toggle visibility, ordering, global and Continue Watching settings and verify persistence after restart. Denied/unavailable providers must leave installed-app launching functional.

Record outcomes separately: **implemented and locally verified**, **device verification required**, **restricted by this firmware's permissions**, and **unsupported through public APIs**. A restricted device is a valid graceful-fallback result, not successful cross-app recommendation access.

Implementation limits: discover at most 64 channel records per installed app (within a global scan of 5,000 channel records), show at most 12 populated visible app rows, and retain at most 40 items per row. Each provider cursor scan examines at most 5,000 records, so exceptionally large catalogs may be truncated. Native nested RecyclerViews recycle rows and cards, retain stable identities, and diff updates asynchronously. The image loader uses a 12 MiB bitmap memory budget, a 16 MiB disk budget, two workers, and at most 64 in-flight image keys; artwork is decoded at the requested target size.

Automated Android tests use an injected source and platform cursors for mapping, malformed records, failures, sorting, deduplication, bounds and safe links. The separate actual-provider probe queries through the target application context and reports only capability states/counts. Build the unminified signed fixture-verification pair with `./build.sh :app:assembleVerification :app:assembleVerificationAndroidTest`; the test runner is `local.tv.launcher.test/androidx.test.runner.AndroidJUnitRunner`. Running instrumentation temporarily exercises recommendation preferences and restores their original values afterward. Fake-source tests are not evidence that another app’s records are accessible.


For a task-owned local emulator, `./verify-android.sh --serial emulator-5560` installs and runs the verification test pair, which uses production sources with shrinking and minification disabled for deterministic fixtures. The actual release APK must be launched and checked separately. The script rejects physical targets; it never changes default Home or system packages. Provider capability results on an emulator do not establish access on a real TV.

## Installed-app search

Search matches visible shared recommendations and discovers exported Android search activities through `SearchManager` searchable metadata, global-search registrations, and `ACTION_SEARCH` handlers. Accessible suggestion providers contribute real content cards under **From your apps**. **Search in apps** offers a separate query handoff; it is not a content match or proof of catalog availability. Suggestion launches remain bound to the publishing app’s exported, permission-accessible activity; legitimate `content:` suggestion links are accepted through this separate validation path. No payloads, selectors, grant flags, or arbitrary actions are copied from providers.

Apps without public searchable integration remain launchable but cannot contribute remote catalog results. Google’s privileged/personalized search index is not replicated. Independent provider workers isolate failures from Cinemeta. Search caps discovery at 32 installed apps, eight candidate activities per app and 20 suggestion rows per query; six provider slots and a 1.8-second deadline bound work and ignore late responses. A provider ignoring cancellation can occupy a slot until it returns; queued searches cannot grow without bound. Refresh, edits and lifecycle changes invalidate obsolete results.

Verification-only `AppSearchTest` exercises Android searchable metadata/provider federation, owner-mapped content activation, URI encoding, stale suppression and launch sanitization. `AppSearchTest.actualAppUidSearchProbe` logs only result/handoff counts under `FudgeAppSearchProbe`; empty results on a device indicate capability limits, not a successful catalog search. Verification-only searchable activity/provider fixtures are absent from release builds.
