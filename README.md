# GABRIEL: Offline Emergency Mesh, GPS, Signed SOS & Maps

GABRIEL is an offline-first emergency communication, signed SOS distress beacon, GPS tracking, and offline vector map application for Android, built on top of the decentralized Bluetooth Low Energy (BLE) mesh stack.

Designed specifically for natural disasters, mountain trekking (Himalayas, Western Ghats), cellular blackouts, and search & rescue group coordination across India.

**100% Offline**: Requires **NO internet connection**. The `android.permission.INTERNET` permission has been completely excised from `AndroidManifest.xml`. All core functionality—mesh chat, SOS beacons, signed acknowledgements, GPS fix acquisition, safe location search, and map rendering—operates completely disconnected from the cloud.

---

## Key Capabilities

### 1. Public Signed Emergency SOS Beacon
- **Zero-Internet GPS Fix**: Pure Android platform `LocationManager` focusing on `GPS_PROVIDER` hardware with live horizontal accuracy (meters) and fix age (seconds).
- **Cryptographic Authenticity**: Every SOS beacon is signed with the user's Ed25519 identity key (`MessageType.SOS` [0x30]). Guarantees tamper-evidence and sender authenticity over multi-hop mesh relays.
- **Truthful Delivery State Machine**: Strictly forward-only lifecycle (`CREATED -> QUEUED -> RELAYED -> ACKNOWLEDGED / CANCELLED / RESOLVED`). Never fakes delivery or rescue guarantees.
- **Payload Data**: Emergency category (`LOST`, `MEDICAL`, `INJURY`, `DANGER`, `OTHER`), latitude, longitude, accuracy, fix timestamp, device battery percentage, and an optional 140-byte emergency message note.

### 2. Authenticated Ed25519 Acknowledgements (`SOS_ACK`)
- Responders can acknowledge any received active SOS with a single tap.
- Dispatches a signed `SOS_ACK` (0x31) packet addressed to the SOS originator.
- **Honesty Disclaimer**: The UI explicitly informs both sender and receiver that an acknowledgement proves only that an individual nearby user received the beacon and pressed Acknowledge; it does not represent official government dispatch or rescue guarantee.

### 3. Outbound BLE Emergency Priority Queue
- Dual-channel broadcast pipeline in `BluetoothPacketBroadcaster`: unbounded `emergencyBroadcastChannel` is always drained before normal traffic.
- Per-link priority queueing in `LinkSendState`: `emergencyPending` packets (`SOS`, `SOS_ACK`) jump ahead of all normal chat or media fragments.
- Link saturation eviction: drops normal packets if a BLE GATT connection queue is congested, ensuring life-critical emergency packets are never starved.

### 4. Offline Vector Maps (MapLibre Native + PMTiles)
- **Local PMTiles Vector Maps**: Scans `context.getExternalFilesDir(null)/maps/` for local `.pmtiles` vector map archives and renders them offline via `pmtiles://file://...`.
- **Bundled Baseline Fallback**: Ships with bundled offline GeoJSON world landmass polygons and national borders (`offline_basemap_style.json`).
- **Interactive Layers**: Visualizes user's real-time GPS position, active SOS emergency pins with tap-to-acknowledge callouts, discovered mesh peers, and curated safe locations.
- **Geodesic Navigation**: Real-time great-circle distance (Haversine formula), true compass azimuth bearing (`0..359°`), and 16-point cardinal compass direction ("NNE", "SW", etc.) to all targets.

### 5. Curated Offline Safe Locations & Emergency POIs (India)
- Bundled database of 23 critical Indian emergency facilities with zero internet requirement:
  - **Himalayan Mountain Rescue & Trauma**: Leh District Hospital (Ladakh), Manali Civil Hospital, IGMC Shimla, AIIMS Rishikesh, SKIMS Srinagar.
  - **National Apex Hospitals**: AIIMS New Delhi, Safdarjung Hospital, KEM Hospital Mumbai, Victoria Hospital Bengaluru, SSKM Hospital Kolkata, Rajiv Gandhi Hospital Chennai, GMCH Guwahati.
  - **National Disaster Response Force (NDRF)**: 8th Bn Ghaziabad (NCR), 5th Bn Pune, 10th Bn Guntur, 2nd Bn Kolkata, 1st Bn Guwahati.
  - **Relief & Cyclone Shelters**: Red Cross Disaster Relief Centres (Chennai, Bhubaneswar, Delhi), Coast Guard Maritime Rescue HQs (Mumbai, Port Blair).
- Fast offline search by name, city, or category, with live proximity sorting from the user's current GPS position.

### 6. Opt-In Signed Peer Location Sharing
- Users can choose to share their location with nearby mesh peers (`MessageType.PEER_LOCATION` [0x32]).
- **Strictly Opt-In**: Disabled by default.
- **Smart Thresholding**: Only broadcasts when moving > 50 meters or every 5 minutes, preserving battery and radio bandwidth.
- Displays peer locations as pins on the offline map with distance and battery level.

### 7. 4-Tab Navigation Shell & Status Strip
- **Navigation Tabs**: `CHAT` (offline mesh messaging), `SOS` (emergency beacon control & received alerts), `MAP` (MapLibre vector maps & safe locations), `NEARBY` (discovered Bluetooth peers & RSSI).
- **Persistent Status Strip**: Real-time display of `[ OFFLINE MESH ]`, Bluetooth state, active peer count, GPS fix quality badge (`GOOD`, `INACCURATE`, `STALE`, `NO FIX`), accuracy in meters, and live fix age.
- **Privacy-Preserving Notifications**: Ongoing foreground service notification alerts of active emergency broadcasts while strictly suppressing GPS coordinates and message text to prevent lockscreen leakage.

---

## Offline Vector Map Preparation

To use high-resolution local offline vector maps in GABRIEL:

1. Download or extract an OpenStreetMap PMTiles extract for India (or your specific state) from [Protomaps](https://protomaps.com/extracts):
   ```bash
   # Example using pmtiles CLI:
   pmtiles extract https://build.protomaps.com/20261025.pmtiles india.pmtiles --bbox=68.1,6.7,97.4,35.7
   ```
2. Push the `.pmtiles` file directly to GABRIEL's offline map directory on your device:
   ```bash
   adb push india.pmtiles /storage/emulated/0/Android/data/com.gabriel.droid/files/maps/
   ```
3. Restart or switch to the Map tab in GABRIEL. The app automatically detects `india.pmtiles` and switches from the baseline fallback map to the vector map.

---

## Build & Test Instructions

### Toolchain Requirements
- OpenJDK 21
- Android SDK (API 34 / compileSdk 37, minSdk 26)

### Build Commands
```powershell
# Set JDK 21 in PowerShell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# Compile Kotlin sources
.\gradlew.bat :app:compileDebugKotlin

# Run all GABRIEL test suites
.\gradlew.bat :app:testDebugUnitTest --tests "com.gabriel.android.sos.*" --tests "com.gabriel.android.location.*" --tests "com.gabriel.android.mesh.EmergencyPriorityQueueTest"

# Assemble Debug APKs
.\gradlew.bat :app:assembleDebug
```

The compiled APKs will be generated under:
`app/build/outputs/apk/debug/`

---

## Crucial Safety & Threat Disclaimers

1. **NO GUARANTEE OF RESCUE**: GABRIEL is an ad-hoc offline peer-to-peer mesh experiment. Packet propagation depends entirely on physical peer density, line-of-sight RF conditions, and device battery levels. It is NOT connected to government 112 dispatch unless a physical user with radio relay access calls 112 on your behalf.
2. **PUBLIC SOS BEACON**: SOS broadcasts are cryptographically signed but **unencrypted over the air**. Anyone within radio range can decode your coordinates, battery percentage, and message. Do not include private credentials or sensitive personal information in SOS notes.
3. **TRUTHFUL ACKNOWLEDGEMENTS**: An acknowledgement (`SOS_ACK`) confirms only that another individual's device received the packet and the user pressed Acknowledge. It does NOT guarantee that search teams are en route.
4. **BATTERY OPTIMIZATIONS**: Android OEM battery killers (MIUI, OneUI, ColorOS) will kill background Bluetooth scanning unless battery optimization is disabled. Ensure GABRIEL is exempt from battery saver settings before entering the backcountry.

---

## License

GABRIEL modifications © 2026. Based on gabriel (Public Domain / GPLv3). Offline map data © OpenStreetMap contributors under the Open Database License (ODbL).
