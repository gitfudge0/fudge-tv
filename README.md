# fudge-tv

An Android TV home screen following the supplied Cinema concept: local Inter typography and cinematic layout, real shared Continue Watching, live TMDB discovery/search, Open-Meteo weather and remote navigation. Native Java hosts a packaged, restricted WebView. Android 12/API 31 minimum, target/compile API 35; package `local.tv.launcher`, version 2.

## Build

Install Android SDK platform/build-tools 35, Java 21, and Node.js for navigation checks. Android Studio's bundled JDK is used on macOS when available; otherwise set `LAUNCHER_JAVA_HOME`. Set `ANDROID_SDK_ROOT` if needed.

Provide a TMDB Read Access Token in an **external** file, default `~/api.key`, or set `TMDB_CREDENTIAL_FILE` to its path. The build selects one unique JWT-shaped token preferentially, otherwise one unique 32-character API key; missing/unreadable/ambiguous input fails without printing its contents. This app is intentionally preconfigured at build time: end users do not enter credentials on the TV. **A credential bundled in an APK is extractable.** Use your own credentials for your builds; do not publish generated APKs with private keys.

Run:

```sh
./build.sh
node tests/navigation-test.js
```

`build.sh` runs release assembly, Java tests and Android lint, verifies the APK signature and prints its SHA-256. It finds cached Gradle 8.7 or downloads and checksum-verifies the official distribution into ignored `.tooling/`; no global Gradle installation is needed. Maven dependencies are resolved from their configured repositories. This bootstrap script is used instead of a checked-in Gradle wrapper binary.

On the first build, an ignored development `launcher-signing.p12` is generated. Keep that key privately for updates to your installation. A different key cannot update an existing installation in place. The original TV operator must preserve their existing signing certificate; a fresh developer key is for a separate installation. The development signing passwords in the scripts are not account credentials.

Generated `BuildConfig.java`, build directories, APKs, signing keys and raw credential files are ignored and must stay outside Git. The local checkout may contain ignored operator state; it is not part of the public project.

## Install and recover safely

First inspect and authorize your own ADB connection. The installer requires explicit serial, expected model, API and profile:

```sh
./build-and-install.sh --serial "$TV_SERIAL" --model "$TV_MODEL" --sdk "$TV_API" --user "$TV_USER"
```

It verifies the target, installs only for the selected profile, checks package/version and cleans up its temporary APK. It does not connect ADB, select default Home, disable packages, grant permissions or change settings. Preview the app through the TV's Apps screen before choosing Home. Never disable a stock launcher or setup/recovery package based on another model's procedure.

See [device recovery](docs/device-recovery.md) for the separate opt-in backup/restore tools. Keep original Home available during evaluation. Restore and verify original Home **before uninstalling** a deployed custom launcher. No private backup, device address, account information or screen capture is included here.

## Data and controls

- Continue Watching uses only actual entries published by retained apps through the TV provider. TV listings permission is optional. Private provider history may remain unavailable. Resume links must belong to their retained owner and resolve to an exported activity; unsupported links open that app instead. Viewing-history titles are never automatically sent to TMDB.
- Discovery/search use TMDB. Search includes returned person known-for titles and exact genre discover queries. Cached/offline/error states remain honest. Availability uses JustWatch via TMDB and does not supply a verified playback link. Open app presents a styled chooser, then opens the selected installed app for manual title search.
- The first Home catalog row visibly exposes all seven retained streaming apps in MRU order; Settings → Your apps offers the same apps. Genuine retained-provider preview channels follow when published; TMDB discovery is a separately labelled section. Optional usage access includes launches outside this home; without it only this launcher's launches count. Rearranging `Policy.PACKAGES` does not pin an order.
- Weather uses Open-Meteo, with editable city (default Pune), without GPS permission. Provider availability region is editable (default IN).
- Settings includes Inputs/Live TV, Wi-Fi, TV settings, optional permissions and safe installed TV apps. Native system dialogs use the platform UI.

The page is loaded from a trusted packaged HTTPS origin. External navigation, frames, popups, file/content access and mixed content are blocked. A main-frame exact-origin bridge accepts bounded operations. Credentials never enter JavaScript or diagnostics. Native network calls are allowlisted, timed out, bounded and cached. There is no analytics, accessibility service, fake playback/progress or continuous catalog polling.

## Source and verification

Presentation: `app/src/main/assets/ui/`; reference CSS is preserved in `reference.css`, with functional remote/error states in `functional.css`. Inter is bundled with its font license. Native bridge, app-owner validation, history and bounded network clients live in `app/src/main/java/local/tv/launcher/`.

Before migration, independent release checks passed: 22 Java tests, eight JavaScript navigation contracts, lint zero errors/seven warnings, signature verification, and exact reference CSS/font checks. Actual TV metrics confirmed a 1920×1080 CSS viewport, Inter 34px/41px at weight 700 and the reference queue geometry. This is evidence for the inspected build, not a promise for every Android TV/WebView. Check your device's viewport, real remote, keyboard, permission-denied and offline behavior after building. After migration, the user explicitly requested larger typography and a visible Home app strip. Functional CSS therefore uses a large-TV type hierarchy (hero 48/56px, body 24/30px, controls 22/28px), widens Continue Watching to 420px, and shortens the hero to keep the first app strip and labels visible; original reference.css remains unchanged as provenance. Gallery is the startup focus: Left/Right changes features, Up enters only Home/Search/Settings, Down enters Continue Watching; further Down moves to apps, published provider rows and TMDB discovery. Up reverses that order. Left/Right changes items within each row. OK on a featured gallery item opens details, with Open app available there. Highlighting a real Continue Watching card updates the gallery from that provider’s title, description and artwork; OK on that selected gallery resumes its owner-validated program or opens its owning app. Left/Right returns to featured discovery. Gallery/top-navigation focus resets the page to the full top. Focus uses neutral scale/elevation/glow instead of blue rectangles. Clock/weather are not focus stops. The latest UI candidate passed an independently forced 53-task release build, 22 Java tests with no failures/errors, JavaScript navigation/startup contracts, four offline recovery-tool tests, and lint with zero errors/seven warnings. Actual TV checks confirmed rendered hero 48/56px and body 24/30px, the 420px queue with three complete compact rows, all seven Home app labels visible, gallery startup focus, and a clean page top. Remote mapping checks covered gallery/navigation/Continue Watching/apps transitions and top restoration; the final physical-remote confirmation by the user remains pending. These observations do not claim user acceptance of the latest revision.

This repository does not include device-specific deployment journals or private viewing-history screenshots. No project-wide license has been selected; third-party assets retain their own rights and licenses. See [third-party notices](THIRD_PARTY_NOTICES.md) and the bundled Inter license. This product uses the TMDB API but is not endorsed or certified by TMDB.
