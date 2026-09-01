# Boreal Android 5.1 — Native

This is a genuine Android application written in Kotlin + Jetpack Compose.
There is no WebView and no HTML/JavaScript UI.

## Native architecture
- Jetpack Compose screens and Android Back handling
- Kotlin coroutines + ViewModel journey-search state
- Transitous MOTIS v6 nationwide timetable search
- Photon UK location autocomplete/geocoding
- MapLibre Native Android (OpenGL) with OpenFreeMap/OpenStreetMap dark style
- Native GeoJSON route overlays: bus service colours, white casing, dashed walking lines and exact stop markers
- Foreground data-sync service while a long journey search is running, allowing Home/app switching without deliberately stopping the search
- Persistent Appearance setting with System default, Light and Dark choices
- System default follows Android's current theme automatically
- Separate Material 3 light and dark colour schemes throughout Compose surfaces and components

## Inherited routing rules
- Search the selected calendar day only
- Leave-at searches departures from the chosen time until the end of that date
- Arrive-by searches arrivals up to the chosen time on that date
- BUS enabled by default
- COACH / RAIL / FERRY / AIR are explicit opt-ins
- RA1, RA2, LGW and OXF are classified as coaches
- Centaur Coaches services are coaches
- FlixBus and National Express are excluded
- 60-minute nationwide timetable sweep
- timetableView=true, maxItineraries=256, maxTransfers=60
- long-walk radius fallback only if no short-walk route exists
- optional Via, avoid terms and day-trip/return break

## Android identity
- applicationId: `com.borealroutes.app`
- minSdk: 26
- targetSdk / compileSdk: 35
- versionCode: 11
- versionName: `5.1-native`

The release keystore is deliberately NOT included in this source archive. GitHub Actions produces an unsigned APK; signing is handled separately outside GitHub.

## Building
Run `./gradlew --no-daemon :app:assembleRelease`, or open the project in a current Android Studio installation and build the `app` module. The project uses Gradle 8.7, AGP 8.6.1, Kotlin 2.0.21 and Jetpack Compose.
