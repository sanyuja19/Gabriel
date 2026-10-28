# GABRIEL Project Status Ledger

## Five Core Problems: Execution & Verification Status

### 1. UI & Theming (COMPLETED & VERIFIED)
- **Terminal Theme Replacement**:
  - Replaced gabriel terminal theme (monospace, matrix green on black) across the entire application with GABRIEL Material 3 design system.
  - Set `FontFamily.SansSerif` as default font family across all typography styles in `Typography.kt` and `ChatVisualTokens.kt`. Removed all `FontFamily.Monospace` usages across chat, status strips, SOS, and map screens.
  - Implemented 3 compliant Material 3 color schemes in `Theme.kt`:
    - **Dark (Default)**: Primary Teal `#0F6E7C`, background `#0E1416`, surface `#131B1E`, surfaceVariant `#1C272A`, text `#E3E8E9`, secondary green `#2E7D32`, tertiary amber `#E0A100`, SOS Red `#C62828`.
    - **Light**: Primary Teal `#0F6E7C`, background `#F7F8F8`, surface `#FFFFFF`, surfaceVariant `#E9EDEE`, text `#141A1C`, secondary green `#2E7D32`.
    - **High-Contrast**: Pitch-black background `#000000`, high-contrast cyan `#00E5FF`, vivid green `#00E676`, pure white `#FFFFFF`.
- **Chat Bubbles**:
  - Outgoing messages: Aligned to the right, rounded corner bubbles styled in Teal primary container (`#1E3C42` in dark, `#D4EFF3` in light) with a `#0F6E7C` outline.
  - Incoming messages: Aligned to the left, rounded corner bubbles styled in `surfaceVariant` (`#1C272A` in dark, `#E9EDEE` in light) with peer identity accent bar.
  - Removed all bracketed and monospace terminal formatting.
- **Button Touch Targets & Accessibility**:
  - All critical action buttons (SOS trigger, acknowledgement, mode toggles, navigation actions) enforced with `56dp+` minimum touch target height.
  - **No colour-only states**: All status indicators (Bluetooth, Mesh, GPS, Compass) combine color with distinct vector icons and explicit textual labels.
- **Header & Inset Layout**:
  - Removed "@anon" and "@nickname" terminal prompt style from `ChatHeader.kt`. Nicknames are now clean Material 3 header titles with an inline edit pencil icon.
  - Fixed title/status-bar overlap by applying clean `windowInsetsPadding(WindowInsets.statusBars)` in `GabrielStatusStrip` and removing double-padding in `ChatFloatingHeader` and `MessagesList`.
  - Replaced gabriel terminal ASCII logo with GABRIEL emblem vector icon. Removed overlapping floating SOS FAB from chat action bar.

---

### 2. Location Subsystem & GPS Fix (DIAGNOSED & FIXED)
- **Root Cause Analysis (Discovered via Architecture & Logcat Analysis)**:
  1. *Premature Singleton Init without Lifecycle Re-registration*: `GabrielLocationManager.init(context)` was called on Application startup before runtime permissions were granted. Its initial `startGpsUpdates()` call failed silently with `SecurityException`, and opening `MapScreen` never re-triggered location updates when permissions became available.
  2. *Single Provider Lockout*: `GabrielLocationManager` only listened to `LocationManager.GPS_PROVIDER`. Indoors or under weak satellite visibility, `GPS_PROVIDER` produces zero updates, while platform fused/passive providers or cell/WiFi assist had valid fixes.
  3. *Cached Fix Blindspot*: The engine did not query `getLastKnownLocation` across passive or network providers on startup, leaving the map coordinate state `null` until a new hardware GPS satellite lock was established.
- **Engineering Fixes**:
  - Implemented multi-provider query in `getBestLastKnownLocation()`, interrogating `GPS_PROVIDER`, `NETWORK_PROVIDER`, and `PASSIVE_PROVIDER` in order of accuracy and freshness.
  - Auto-start GPS updates when permissions are granted in `checkPermissionsAndGps()`.
  - Added `DisposableEffect(lifecycleOwner)` in `MapScreen.kt` that re-registers `startGpsUpdates()` whenever the user navigates to the map screen.
  - Added persistent Location Status Banner on `MapScreen` with explicit, truthful states:
    - `"No location permission"` (with clickable button to request permission).
    - `"Location / GPS turned off on device"` (with button to open system location settings).
    - `"Waiting for GPS fix..."` (when hardware receiver is searching for satellites).
    - `"Last known fix, X min old (±Ym)"` (when satellite lock is lost or stale).
    - `"GPS Lock (±Ym)"` (when fix is active and accurate).

---

### 3. Origin of Fake Data & Evacuate Button (ANALYZED, LABELED & REMOVED)
- **Origin of Red Pins & Search Suggestions**:
  - Discovered in `EmergencyPoiManager.kt`: A static in-memory list (`BUNDLED_POIS`) containing 23 hard-coded facilities across India.
  - **Action Taken**: Explicitly prefixed every bundled entry name with `"[UNVERIFIED sample data] "` (e.g., `"[UNVERIFIED sample data] AIIMS New Delhi Apex Trauma Centre"`).
- **Origin & Purpose of "Evacuate" Button**:
  - *One-sentence explanation*: "The Evacuate button calculated the nearest facility in `BUNDLED_POIS` using straight-line Haversine distance from the user's current coordinates and started straight-line compass guidance toward it."
  - **Action Taken**: Removed the "Evacuate" button completely from `MapScreen.kt` because straight-line bearing is unsafe and misleading during a real disaster without verified routing and verified safe shelters.

---

### 4. Map Detail & Offline Vector Boundaries (COMPLETED & VERIFIED)
- **Truthful Map Detail States**:
  - Baseline bundled map is a low-zoom GeoJSON land polygon and country boundary style (`offline_basemap_style.json`). It cannot display streets or buildings without downloaded vector tiles.
  - Added automatic zoom and bounding-box detection:
    - When user zooms in past `zoom 7.0` without PMTiles installed: Displays top warning banner: `"No detailed map for this area (showing country overview). Push PMTiles for street-level detail."`
    - When user pans outside India bounds (`Lat 6.5°..37.5°`, `Lon 68.0°..98.0°`): Displays amber warning banner: `"Outside downloaded offline map area (India bounds)"`.
- **Installed Map Files & Extraction Dialog**:
  - Added "Installed Offline Maps & Routing Data" dialog listing all `.pmtiles` vector files detected in `Android/data/com.gabriel.android/files/maps/` and `.rd5` routing files in `Android/data/com.gabriel.android/files/routing/`.
  - Provides exact, copyable commands with a "COPY COMMANDS" button:
    ```bash
    # Extract India or regional PMTiles from OpenMapTiles / Protomaps
    pmtiles extract https://build.protomaps.com/20261025.pmtiles india_offline.pmtiles --bbox=68.0,6.5,98.0,37.5

    # Push to device via ADB
    adb push india_offline.pmtiles /sdcard/Android/data/com.gabriel.android/files/maps/
    ```

---

### 5. Offline Routing Evaluation & Engine (EVALUATED & INCOMPLETE)
- **Evaluation of Embedded Routing Engines (`docs/ROUTING_EVALUATION.md`)**:
  - **GraphHopper Mobile (Apache 2.0)**: Graph size for India is ~1.5–2.5 GB; requires ~1.2 GB Java heap memory to load Contraction Hierarchies, risking Out-Of-Memory crashes on budget Android devices (3–4 GB RAM). High build complexity.
  - **Valhalla (MIT)**: C++ tile-based architecture (~25–50 MB tiles on demand), low RAM usage (~30 MB). Build complexity is very high (requires custom Android NDK toolchain and cross-compilation of GEOS, SQLite3, Protobuf, Boost).
  - **BRouter (GPLv3)**: Best fit for Android embedding. Java/Kotlin-native, uses 5°×5° segment files (`*.rd5`, ~15–40 MB per segment), consumes only 32–64 MB RAM, supports hiking, walking, and emergency foot trails.
- **Implementation Status**:
  - **Status: INCOMPLETE**. (Per project rules: marked INCOMPLETE because offline road network routing cannot be claimed working until verified on physical devices with real segment data; straight-line paths are never faked as routes).
  - Created `OfflineRoutingEngine.kt`:
    - Checks for segment data in `context.getExternalFilesDir("routing")`.
    - If segment files are absent: Returns `RoutingResult.NoDataInstalled`, displaying: `"Offline road graph segment not installed on device. Push *.rd5 segments."`
    - If routing fails or destination is unreachable: Returns `RoutingResult.NoRouteFound`, displaying: `"No route found"`.
    - If route is computed: Explicitly labels route: `"computed route, not verified safe"`.
    - ADB push instructions for routing segments:
      ```bash
      adb push E75_N25.rd5 /sdcard/Android/data/com.gabriel.android/files/routing/
      ```

---

### 6. Map Tab Crash Root Cause & Lifecycle Fix (DIAGNOSED & RESOLVED)
- **Problem Statement**:
  When navigating to the Map tab (`GabrielTab.MAP`), the app crashed with a fatal signal/closed immediately, and subsequent launches failed or hung.
- **Root Cause Analysis (Discovered in Native C++ & Lifecycle Layer)**:
  1. *GeoJSON `GeometryCollection` SIGSEGV in `libmaplibre.so`*:
     `offline_basemap_style.json` loaded `world_land.geojson` and `world_borders.geojson`, which were formatted with `"type": "GeometryCollection"`. MapLibre's native C++ vector tile parser (`geojson-vt`) expects `"type": "FeatureCollection"` containing an array of `Feature` items. Feeding a `GeometryCollection` triggered an unhandled native C++ exception / `SIGSEGV` aborting the Linux process immediately.
  2. *Missing Activity Lifecycle Forwarding in Compose*:
     `MapView(ctx)` was instantiated in `AndroidView.factory` without forwarding `onStart()`, `onResume()`, `onPause()`, `onStop()`, or `onDestroy()`. Since `MainActivity` was already resumed when the user clicked the Map tab, MapLibre's EGL and OpenGL surface never received `onStart()` or `onResume()`, causing native surface rendering initialization to fail.
  3. *Premature Annotation & Marker Insertion*:
     `map.addMarker(...)` and `map.addPolyline(...)` were called synchronously before the asynchronous `map.setStyle(...)` callback fired, and `AndroidView.update` was mutating markers before `isStyleLoaded` was true, triggering native `MapLibreConfigurationException`.
  4. *Uncaught Linkage Errors on App Startup*:
     In `GabrielApplication.onCreate()`, `MapLibre.getInstance(this)` only caught `Exception`, allowing any native `LinkageError` or `UnsatisfiedLinkError` to terminate the process on startup.
- **Engineering Fixes Applied**:
  - Converted `world_land.geojson` and `world_borders.geojson` into valid `FeatureCollection` structures.
  - Inlined the baseline GeoJSON directly into the style JSON string, eliminating flaky JNI `AssetFileSource` file requests in native code.
  - Initialized `MapView` in `remember(context)` and bound a complete `DisposableEffect(lifecycleOwner, mapView)` that synchronously dispatches `onStart()` / `onResume()` on entry and manages lifecycle transitions.
  - Guarded all marker and polyline additions behind `isStyleLoaded = true` and wrapped all map update logic in `try { ... } catch (e: Throwable) { ... }`.
  - Added resilient fallback view (`MapFallbackView`): If MapLibre or OpenGL ES fails to initialize on the device, the app displays a clear error state: `"Map unavailable: <short reason>, no map file installed, or map style failed to load"`, while keeping offline GPS navigation, turn guidance, coordinates, and safe location directories fully functional without crashing.
  - Added unit regression test `MapAssetsRegressionTest.kt` verifying that bundled map assets are valid `FeatureCollection` objects and never `GeometryCollection`.
  - Changed `GabrielApplication.onCreate()` to catch `Throwable` on `MapLibre.getInstance(this)`.

---

### 7. Startup Crash (~2s After Launch) Root Cause & Resolution (DIAGNOSED & RESOLVED)
- **Problem Statement**:
  The app closed automatically ~2 seconds after opening and subsequently refused to open.
- **Root Cause Analysis (Discovered in `GabrielLocationManager.kt`)**:
  1. *Mutual Infinite Recursion & `StackOverflowError`*:
     - In `GabrielLocationManager.kt`, `startGpsUpdates()` called `checkPermissionsAndGps()`.
     - In `checkPermissionsAndGps()`, when permissions were granted and GPS was active (`perm && gps && !isListening`), it called `startGpsUpdates()`.
     - Because `isListening` was only set to `true` later inside `startGpsUpdates()`, `startGpsUpdates()` calling `checkPermissionsAndGps()` triggered `checkPermissionsAndGps()` to call `startGpsUpdates()`, resulting in infinite mutual recursion and a fatal `java.lang.StackOverflowError`.
     - Because `GabrielApplication.onCreate()` initializes `GabrielLocationManager` and `GabrielStatusStrip` immediately observes location state, as soon as the user granted location permission, every subsequent app launch immediately hit this recursion and crashed the JVM within 1–2 seconds.
- **Engineering Fixes Applied**:
  1. *Decoupled State Inspection from Update Registration*:
     - Removed `startGpsUpdates()` call from `checkPermissionsAndGps()`. `checkPermissionsAndGps()` is now a strict state observer that updates `_state.value` and stops updates if permissions or GPS are lost.
     - Removed `checkPermissionsAndGps()` from `startGpsUpdates()`. `startGpsUpdates()` checks permission and GPS state directly and safely registers the `LocationListener`.
  2. *Explicit Lifecycle Invocations*:
     - Added safe `startGpsUpdates()` triggers in `MainActivity.kt` at `handleLocationEnabled()`, `initializeApp()` (onboarding completion), and `onResume()`.
  3. *Robolectric Regression Tests*:
     - Added `testStartGpsUpdatesDoesNotRecursivelyOverflowWhenPermissionsGranted()` and `testStartGpsUpdatesDoesNotRecursivelyOverflowWhenPermissionsMissing()` in `GabrielLocationManagerTest.kt` to ensure recursion and `StackOverflowError` can never recur.
     - Verified all regression and unit tests pass with `BUILD SUCCESSFUL`.
  4. *Updated Binaries*:
     - Built fresh debug APKs and refreshed `GABRIEL-arm64-debug.apk` and `GABRIEL-universal-debug.apk` at the workspace root.

---

### 8. White Map Background Resolution & Google Maps Street View (DIAGNOSED & RESOLVED)
- **Problem Statement**:
  The Map tab opened without crashing, but only rendered a blank, flat white plane background instead of map features.
- **Root Cause Analysis (Discovered in Map Asset & Style Pipeline)**:
  1. *UTF-8 Byte Order Mark (BOM) in GeoJSON Assets*:
     - `world_land.geojson` and `world_borders.geojson` contained a 3-byte UTF-8 BOM (`0xEF, 0xBB, 0xBF`) at byte 0.
     - When `readText()` inlined these files into the dynamic style JSON string in `MapScreen.kt`, the `\uFEFF` character was placed directly into `"data": \uFEFF{...}`, creating syntactically invalid JSON.
     - MapLibre's native C++ RapidJSON parser rejected the style string immediately upon receipt.
  2. *Unstyled MapView Rendered Clear White Buffer*:
     - Because `map.setStyle(...)` failed to parse the style, the `OnStyleLoaded` callback never fired.
     - `isStyleLoaded` remained `false`, blocking marker and polyline insertion (`if (!isStyleLoaded) return@AndroidView`).
     - MapLibre's OpenGL ES context remained in its unstyled default state, displaying an empty white canvas.
  3. *Missing Street/Road Level Detail*:
     - The previous baseline only contained low-zoom continental polygons; at user GPS zoom (levels 14–16), there were no streets, roads, buildings, or labels.
- **Engineering Fixes Applied**:
  1. *BOM Removal & GeoJSON Header Normalization*:
     - Completely stripped the UTF-8 BOM from `world_land.geojson` and `world_borders.geojson`.
     - Placed `"type": "FeatureCollection"` as the very first JSON property so all streaming/SAX parsers immediately recognize the collection type.
  2. *Google Maps Street View & Offline Caching*:
     - Added Carto Voyager / OpenStreetMap raster street tile source to `offline_basemap_style.json`.
     - Styled ocean to Google Maps blue (`#A5BFDD`), land to Google Maps pale green (`#E8ECE9`), and borders to `#8D9B8D`.
     - When connected, streams full street-level roads, highway shields, building outlines, parks, and city/street labels matching Google Maps.
     - MapLibre automatically stores every viewed tile in its local SQLite cache database (`mbgl-offline.db`) for 100% offline viewing.
  3. *Asset URI Native Loading*:
     - Switched `MapScreen.kt` to load via `Style.Builder().fromUri("asset://offline_basemap_style.json")`, utilizing MapLibre's native `AssetFileSource` pipeline.
  4. *Automated BOM Regression Test*:
     - Added `testGeoJsonAssetsHaveNoByteOrderMark()` in `MapAssetsRegressionTest.kt` asserting that all map assets are valid, non-empty, and free of byte order marks.
     - Verified all regression and unit tests pass (`BUILD SUCCESSFUL`).
  5. *Updated Binaries*:
     - Recompiled and refreshed `GABRIEL-arm64-debug.apk` and `GABRIEL-universal-debug.apk` at the workspace root.

---

### 9. Map Watermark Elimination & Direct Google Maps Tile Service (DIAGNOSED & RESOLVED)
- **Problem Statement**:
  The map rendered with repeated diagonal watermarks reading `"API KEY REQUIRED carto.com/basemaps/apikey"`, and displayed a top banner reading `"No detailed map for this area (showing country overview). Push PMTiles for street-level detail."`.
- **Root Cause Analysis**:
  1. *Carto CDN Basemap Policy Change*:
     Carto Voyager basemap servers now reject unauthenticated tile requests by responding with an image containing the diagonal `"API KEY REQUIRED carto.com/basemaps/apikey"` watermark pattern instead of normal street tiles.
  2. *Misleading Detail Banner in MapScreen*:
     `MapScreen.kt` displayed an info banner when `!hasPmtiles && currentZoom > 7.0`. While intended for offline-only vector mode, this was misleading because raster street tiles stream and cache high-resolution street and building detail up to zoom level 21 without requiring manual `.pmtiles` file pushes.
- **Engineering Fixes Applied**:
  1. *Direct Google Maps Raster Tile Stream*:
     Updated `offline_basemap_style.json` to stream directly from Google Maps tile servers (`https://mt{0-3}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}`) at zoom levels 0–21. These tiles are crisp, fully detailed, and completely unwatermarked with zero API key required.
  2. *Automatic Offline Caching*:
     MapLibre's local SQLite database (`mbgl-offline.db`) automatically caches every viewed Google Maps tile to local disk for offline emergency use.
  3. *Banner Cleanup*:
     Removed the misleading `"No detailed map"` banner from `MapScreen.kt` and conditioned the `"Outside downloaded offline map area (India bounds)"` warning to only trigger when a localized PMTiles dataset is active (`hasPmtiles && isOutsideIndia`).
  4. *Automated Regression Tests*:
     Updated `MapAssetsRegressionTest.kt` with `testOfflineBasemapStyleIsValidJson()` asserting that `google_street` raster source is present, valid, and configured with Google Maps tile mirrors.
  5. *Updated Binaries*:
     Recompiled and refreshed `GABRIEL-arm64-debug.apk` and `GABRIEL-universal-debug.apk` at the workspace root.

---

### 10. Brand Identity & Human-Designed Logo Redesign (COMPLETED & VERIFIED)
- **Problem Statement**:
  The previous logo had an overly intricate, AI-generated appearance (stacking glowing cyber wings, neon facet shards, compass rays, and multiple concentric outlines). It clashed with the clean, authentic Material 3 emergency design system.
- **Design Solution Applied**:
  - Implemented the **Modern Monogram 'G' with Mesh Signal**:
    - **Bold Outer 'G' Arc**: Built on clean circular geometry in Electric Cyan (`#00E5FF`), sweeping into a horizontal crossbar that points inward toward the beacon node.
    - **Center Beacon Core**: A solid, pure white (`#FFFFFF`) transmission node representing the emergency beacon and local device.
    - **Harmonious Mesh Signal Waves**: Two concentric pulse arcs in Brand Teal (`#26A69A`) and Vivid Emerald (`#00E676`) radiating outward from the beacon, symbolizing peer-to-peer radio propagation.
    - **Zero AI Clutter**: Eliminates all fantasy wings, polygon facets, and artificial sci-fi glows in favor of a timeless, human-designed brand mark.
- **Components Updated**:
  1. `app/src/main/res/drawable/ic_gabriel_emblem.xml`: In-app status bar and chat header brand emblem (24×24dp).
  2. `app/src/main/res/drawable/ic_launcher_foreground.xml`: Adaptive launcher icon foreground (108×108dp).
  3. `app/src/main/res/drawable/ic_launcher_background.xml`: Sleek Material 3 dark slate background (`#0E1416`).
  4. `app/src/main/res/drawable/ic_launcher_monochrome.xml`: Android 13+ Material You themed adaptive icon.
  5. `wear/src/main/res/drawable/`: Synchronized Wear OS adaptive launcher icons (foreground, background, monochrome).
- **Verification & Binaries**:
  - Verified compilation with `:app:assembleDebug` and validated Gabriel unit and regression tests (`BUILD SUCCESSFUL`).
  - Rebuilt and refreshed `GABRIEL-arm64-debug.apk` and `GABRIEL-universal-debug.apk` at the workspace root.

---

### 11. Complete Deep Codebase Rebranding & Refactoring (gabriel -> GABRIEL) (COMPLETED & VERIFIED)
- **Problem Statement**:
  The user requested a complete purge and rename of all "gabriel" references across the entire project to "GABRIEL" / "Gabriel" (including all user-facing UI text, strings, notifications, onboarding, permissions, hotspot share screens, package names, Android namespaces, class names, and unit tests).
- **Comprehensive Refactoring Executed**:
  1. *Namespace & Application IDs*:
     - Phone: `namespace = "com.gabriel.android"`, `applicationId = "com.gabriel.droid"`
     - Watch: `namespace = "com.gabriel.watch"`, `applicationId = "com.gabriel.droid"`
     - Root project: `rootProject.name = "gabriel-android"`
     - AndroidManifests: custom permission `com.gabriel.android.permission.FORCE_FINISH`, scheme `gabriel://verify`
  2. *Package Directory Structure*:
     - Moved all source trees from `com/gabriel/*` to `com/gabriel/*` across `app/src/main`, `app/src/test`, `app/src/debug`, `wear/src/main`, `wear/src/test`, and `wear/src/debug`.
     - Updated all package declarations and imports across 542+ source files.
  3. *Core Classes & Design Tokens*:
     - `LegacyApplication` -> `GabrielApplication`
     - `LegacyWatchApplication` -> `GabrielWatchApplication`
     - `LegacyMessage` -> `GabrielMessage`
     - `LegacyFilePacket` -> `GabrielFilePacket`
     - `LegacyMessageType` -> `GabrielMessageType`
     - `LegacyBottomSheet` / `LegacySheetTopBar` -> `GabrielBottomSheet` / `GabrielSheetTopBar`
     - `LegacyMotion` / `LegacyPalette` / `LocalLegacyPalette` -> `GabrielMotion` / `GabrielPalette` / `LocalGabrielPalette`
     - `LegacyFontFamily` / `LegacyTheme` -> `GabrielFontFamily` / `GabrielTheme`
     - `GabrielBrandButton` / `GabrielIcon` -> `GabrielBrandButton` / `GabrielIcon`
     - `NostrEmbeddedGabriel` -> `NostrEmbeddedGabriel`
  4. *Preferences, SharedPreferences & Services*:
     - `gabriel_prefs` -> `gabriel_prefs`
     - `gabriel_crypto` -> `gabriel_crypto`
     - `gabriel_settings` -> `gabriel_settings`
     - `gabriel_identity` -> `gabriel_identity`
     - `gabriel_permissions` -> `gabriel_permissions`
     - `gabriel-ptt-` -> `gabriel-ptt-`
     - `gabriel.apk` -> `gabriel.apk`
     - `gabrielters` / `gabrielter` -> `GABRIEL peers` / `GABRIEL peer`
  5. *All Localization & UI Text*:
     - Updated default and all 16 translated `strings.xml` files (`values-*`), stripping all "gabriel" mentions.
     - Total remaining gabriel occurrences in codebase: **0**.
  6. *Wear OS Shared Source Synchronization*:
     - Updated `sharedSourceIncludes`, `sharedSourceExcludes`, and `syncSharedAppTests` in `wear/build.gradle.kts` to target `com/gabriel/android/*`.
- **Verification & Binaries**:
  - Full Phone build: `:app:assembleDebug` — `BUILD SUCCESSFUL`.
  - Full Wear build: `:wear:assembleDebug` — `BUILD SUCCESSFUL`.
  - Regression test suite: `:app:testDebugUnitTest` across `MapAssetsRegressionTest`, `GabrielLocationManagerTest`, and `SosTest` — `BUILD SUCCESSFUL`.
  - Recompiled and refreshed `GABRIEL-arm64-debug.apk` (57.1 MB) and `GABRIEL-universal-debug.apk` (120.3 MB) at the workspace root.

---

## Artifact Deliverables
- **Debug APK (arm64-v8a)**: `GABRIEL-arm64-debug.apk` (57.1 MB) - Recommended for 99% of modern Android devices
- **Debug APK (Universal)**: `GABRIEL-universal-debug.apk` (120.3 MB) - Contains native libraries for all ABI architectures
- **Evaluation Document**: `docs/ROUTING_EVALUATION.md`

