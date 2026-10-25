# Third-party notices

AndroidX WebKit 1.12.1, RecyclerView 1.4.0 and AndroidX transitive dependencies are provided by the Android Open Source Project under Apache License 2.0. Sources and individual notices: https://android.googlesource.com/platform/frameworks/support/ . Gradle and Android build tools are build-time components with their own distributions and licenses. JUnit 4.13.2 is a build-time dependency under Eclipse Public License 1.0. Their normal distribution artifacts remain authoritative for included notices.

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
JUnit license: https://junit.org/junit4/license.html

Inter Variable is bundled from the user-supplied Cinema concept. Its SIL Open Font License is included at `app/src/main/assets/ui/assets/Inter-LICENSE.txt`. The supplied concept’s CSS, presentation and artwork are included to implement the user’s requested reference. No additional rights in third-party film artwork are asserted.

Movie and series metadata is supplied by the official Cinemeta add-on, https://v3-cinemeta.strem.io/manifest.json . Metadata and artwork belong to their respective owners. Stremio is a separate installed application; this launcher only opens its registered title-detail links and provides no streams or playback engine.

Weather/geocoding is supplied by Open-Meteo and credited in Settings. See https://open-meteo.com/ . The `org.json:json` dependency is used only by JVM tests (public-domain JSON-java distribution); Android supplies the runtime JSON implementation.

Runtime app banners and names are read from installed applications. The earlier generated `assets/scenic.jpg` remains as a legacy source asset; the Cinema presentation uses the bundled reference art and live metadata instead.
