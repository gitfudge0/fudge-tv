# Third-party notices

AndroidX RecyclerView 1.4.0 and its transitive AndroidX dependencies are provided by the Android Open Source Project under Apache License 2.0. Sources and component notices: https://android.googlesource.com/platform/frameworks/support/ . AndroidX WebKit is not a dependency. Gradle and Android build tools are build-time components with their own distributions and licenses; their distribution artifacts remain authoritative.

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- JUnit 4.13.2, used for tests: https://junit.org/junit4/license.html
- Google Java Format 1.24.0, used by `format.sh`: https://github.com/google/google-java-format/blob/v1.24.0/LICENSE

Inter Variable is bundled as the native Android font `app/src/main/res/font/inter.ttf`, obtained from Google Fonts. Its SIL Open Font License is included at `app/src/main/assets/fonts/Inter-LICENSE.txt`. The native interface follows the user-approved wallpaper and input-row mock; no web presentation or WOFF2 font is packaged.

The fixed mountain wallpaper is the unmodified user-selected Unsplash photograph, bundled locally as `app/src/main/res/drawable-nodpi/home_wallpaper.jpg`. Source: https://images.unsplash.com/photo-1590142035743-0ffa020065e6 . Unsplash license: https://unsplash.com/license . No runtime wallpaper request is made.

Movie and series metadata is supplied by the official Cinemeta add-on, https://v3-cinemeta.strem.io/manifest.json . Metadata and artwork belong to their respective owners. Stremio is a separate installed application; this launcher opens its registered title-detail links and provides no streams or playback engine.

Runtime app banners and names come from installed applications. Continue Watching/provider artwork comes from records accessible through Android's TV Provider. No additional rights in third-party artwork are asserted.

The `org.json:json` dependency is used only by JVM tests (the public-domain JSON-java distribution); Android supplies the runtime JSON implementation. Weather/geocoding services are not used.
