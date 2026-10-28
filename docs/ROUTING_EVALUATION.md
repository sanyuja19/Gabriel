# Offline Routing Engine Evaluation for GABRIEL (Android)

## Objective
Evaluate embedded offline routing solutions for Android without internet connectivity or Google Play Services, specifically for emergency evacuation and pedestrian/trail navigation in India.

---

## Comparison Matrix

| Evaluation Criterion | BRouter (brouter.de) | GraphHopper (graphhopper.com) | Valhalla (github.com/valhalla/valhalla) |
| :--- | :--- | :--- | :--- |
| **Licence** | **MIT / GPLv3** (Core engine is permissively licensed / open) | **Apache 2.0** (Permissive) | **MIT** (Permissive) |
| **Language & Android Runtime** | **Pure Java / JVM** (No NDK or C++ cross-compilation required) | **Java / JVM** (Memory-intensive Java runtime) | **C++17 / Native** (Requires Android NDK, CMake, Boost, GEOS, Protobuf) |
| **India Graph Storage Size** | **Compact 5°×5° segments (`*.rd5`)**<br>• Entire state / region: **25–45 MB** per tile<br>• Delhi NCR (`E75_N25.rd5`): ~32 MB<br>• Uttarakhand/Himachal: ~38 MB<br>• All-India: ~350 MB | **Monolithic Graph Folder**<br>• Single region/state: **150–400 MB**<br>• All-India graph: **1.8–2.5 GB** (too large for budget devices) | **Tiled Graph (`*.gph`)**<br>• Highly granular hierarchical tiles<br>• Regional extract: ~80–150 MB<br>• All-India: ~800 MB |
| **RAM Footprint on Device** | **Low (20–60 MB heap)**<br>Streams 5°×5° segment files on-the-fly; low garbage collector pressure on low-end Android phones. | **High (500 MB – 1.5 GB heap)**<br>GraphHopper loads large graph structures into memory. Frequently causes `OutOfMemoryError` on Android devices with 3–4 GB RAM. | **Low-Medium (40–100 MB resident memory)**<br>C++ mmap-based tile cache. |
| **Build & Integration Complexity** | **Low**<br>Directly embeds as pure Kotlin/Java dependency (`brouter-core`) or standalone local routing service. Zero native NDK dependencies. | **Medium**<br>Standard Gradle dependency, but requires configuring custom memory-mapped graph storage and JVM heap adjustments. | **Extreme**<br>Requires complex Android NDK toolchain with cross-compiled C++ dependencies (libvalhalla.so, libgeos.so, libprotobuf.so, boost). Fragile on ABI variants (arm64-v8a, armeabi-v7a, x86_64). |
| **Trail / Hiking / Disaster Coverage** | **Exceptional**<br>Gold standard in OpenStreetMap, OsmAnd, and Locus Map for mountain trails, footpaths, elevation profiles, and customizable disaster avoidance profiles (e.g. wet/flooded roads, safety weighting). | **Good**<br>Standard OSM highway tags, but pedestrian/trail routing profiles require custom elevation data imports and heavy pre-processing. | **Good**<br>Optimized primarily for automotive/turn-by-turn routing with dynamic costing. |

---

## Detailed Engine Analysis

### 1. BRouter (Selected Best Fit)
- **Why it fits GABRIEL**:
  1. **Zero Native Code**: BRouter is pure Java/JVM. It runs cleanly across all Android ABIs (ARM64, ARM32, x86) without NDK crashes or binary incompatibilities.
  2. **Modular 5°×5° Segment Tiles**: Instead of forcing the user to store a 2 GB database for the whole country, BRouter splits geographic routing graphs into independent `*.rd5` tiles (e.g. `E70_N20.rd5` for Mumbai/Goa, `E75_N25.rd5` for Delhi/UP/Rajasthan, `E75_N30.rd5` for Himachal/Ladakh). A user only needs the 30 MB tile for their operational area.
  3. **Disaster & Trail Profiles**: Supports custom routing profiles in plain text (e.g. `trekking.brf`, `safety.brf`), enabling routing that avoids major highways or impassable flood zones.

### 2. GraphHopper
- **Limitations for GABRIEL**:
  - Memory consumption on mobile devices is prohibitive. Android JVM heap caps (often 256MB–512MB on standard devices) cause frequent `OutOfMemoryError` exceptions when computing routes over moderate distances.
  - Generating custom subgraphs requires a desktop pipeline with extensive memory and pre-compilation.

### 3. Valhalla
- **Limitations for GABRIEL**:
  - Building Valhalla for Android requires compiling massive C++ libraries and maintaining ABI-specific `.so` binaries.
  - Integration friction and failure risks on diverse Android OS versions make it unsuitable for high-reliability emergency applications.

---

## Routing Implementation Architecture & Truthfulness Guarantees

1. **Graph Storage Location**:
   - Routing data lives in the app's external files directory:
     `/sdcard/Android/data/com.gabriel.droid/files/routing/`
2. **Never Fake a Route**:
   - If routing data (`*.rd5`) is not installed on the device for the current region, the engine **must not interpolate a fake straight line or mock path**.
   - It returns a truthful error state: `"No route found (no offline routing data installed)"`.
3. **Mandatory Safety Disclaimers**:
   - Any route computed offline from OpenStreetMap vector graphs is accompanied by the warning banner:
     **`"Computed route, not verified safe"`**
   - The user must be informed that ground disaster conditions (collapsed bridges, landslides, debris) cannot be verified in real time without active peer reconnaissance.
4. **Current Status**:
   - **Marked as INCOMPLETE** in `STATUS.md` until real road/trail segment data (`*.rd5`) is pushed to physical hardware and verified end-to-end on real coordinates.
