# GABRIEL: Offline Emergency Mesh, GPS, Signed SOS & Offline Maps

GABRIEL is an offline-first emergency communication, signed SOS distress beacon, GPS tracking, and offline vector map application for Android, powered by a decentralized Bluetooth Low Energy (BLE) mesh network.

Designed for natural disasters, mountain expeditions (Himalayas, Western Ghats), cellular blackouts, and search & rescue coordination.

**100% Offline by Design**: Requires **zero internet connection**. The `android.permission.INTERNET` permission has been completely excised from the application manifest. All functionality—mesh messaging, SOS distress beacons, signed peer acknowledgements, hardware GPS tracking, emergency facility search, and vector map rendering—operates completely disconnected from the cloud.

---

## Key Capabilities

### 1. Modern Material 3 Interface & Theming
- **Material 3 Design System**: Purpose-built UI designed for readability, high visibility, and fast interaction during emergencies.
- **Three Dedicated Color Schemes**:
  - **Dark (Default)**: Low-glare dark theme optimized for night operations and battery conservation (`#0F6E7C` Teal primary, `#0E1416` background).
  - **Light**: High-luminance daylight theme for direct sunlight visibility.
  - **High-Contrast**: Pitch-black (`#000000`) background with vivid cyan (`#00E5FF`) and high-visibility green for maximum contrast and low-power OLED devices.
- **Accessible Touch Targets**: Critical emergency actions (SOS triggers, acknowledgements, location toggles) enforce a minimum 56dp+ touch target height for reliable operation with gloved, wet, or trembling hands.
- **Accessible Status Indicators**: No color-only states; every status indicator pairs distinct vector iconography with explicit textual status labels.
- **Streamlined 4-Tab Navigation**: Clean navigation between `CHAT` (offline mesh messaging), `SOS` (emergency beacon control & received alerts), `MAP` (vector maps & safe locations), and `NEARBY` (discovered Bluetooth peers & signal strength).
- **Persistent Real-Time Status Strip**: Always-visible top status bar displaying `[ OFFLINE MESH ]`, Bluetooth radio state, active peer count, GPS fix quality badge (`GOOD`, `INACCURATE`, `STALE`, `NO FIX`), horizontal accuracy in meters, and fix age.

### 2. Public Signed Emergency SOS Beacon
- **Zero-Internet GPS Fix**: Direct Android platform hardware GPS acquisition with live accuracy radius (meters) and fix age (seconds). Multi-provider fallback interrogates `GPS_PROVIDER`, `NETWORK_PROVIDER`, and `PASSIVE_PROVIDER` to ensure immediate positioning.
- **Cryptographic Authenticity**: Every SOS beacon is signed with the sender's Ed25519 identity key (`MessageType.SOS` [0x30]), guaranteeing tamper-evidence and sender authenticity across multi-hop mesh relays.
- **Truthful Delivery State Machine**: Strictly forward-only lifecycle (`CREATED -> QUEUED -> RELAYED -> ACKNOWLEDGED / CANCELLED / RESOLVED`). Never fakes delivery or rescue guarantees.
- **Beacon Payload**: Emergency category (`LOST`, `MEDICAL`, `INJURY`, `DANGER`, `OTHER`), latitude, longitude, accuracy, fix timestamp, device battery percentage, and an optional 140-byte emergency note.

### 3. Authenticated Ed25519 Acknowledgements (`SOS_ACK`)
- Responders can acknowledge any received active SOS beacon with a single tap.
- Dispatches a cryptographically signed `SOS_ACK` (0x31) packet addressed to the SOS originator.
- **Truthful Acknowledgement Disclaimer**: Both sender and receiver are explicitly informed that an acknowledgement confirms only that an individual nearby user received the packet and pressed Acknowledge; it does not represent official government dispatch or a rescue guarantee.

### 4. Outbound BLE Emergency Priority Queue
- **Dual-Channel Broadcast Pipeline**: Unbounded `emergencyBroadcastChannel` in `BluetoothPacketBroadcaster` is always drained before normal chat or media traffic.
- **Per-Link Priority Queueing**: Emergency packets (`SOS`, `SOS_ACK`) jump ahead of all normal chat messages and file fragments in `LinkSendState`.
- **Congestion Mitigation**: Automatically evicts normal traffic if a BLE GATT connection queue becomes congested, ensuring life-critical emergency packets are never starved.

### 5. Offline Vector Maps (MapLibre Native + PMTiles)
- **Local PMTiles Vector Maps**: Scans local app storage (`Android/data/com.gabriel.droid/files/maps/`) for `.pmtiles` vector map archives and renders them offline via `pmtiles://file://...`.
- **Bundled Baseline Fallback**: Ships with bundled offline GeoJSON world landmass polygons and national borders (`offline_basemap_style.json`).
- **Interactive Map Layers**: Visualizes real-time user GPS position, active SOS emergency pins with tap-to-acknowledge callouts, discovered mesh peers, and curated emergency locations.
- **Geodesic Navigation**: Real-time great-circle distance (Haversine formula), true compass azimuth bearing (`0..359°`), and 16-point cardinal compass direction ("NNE", "SW", etc.) to all targets.
- **Transparent Map Detail Warnings**: Automatic detection alerts users when zooming beyond baseline map detail or panning outside offline map boundaries.

### 6. Curated Offline Safe Locations & Emergency POIs
- Bundled database of 23 critical emergency facilities across India operating 100% offline:
  - **Himalayan Mountain Rescue & Trauma**: Leh District Hospital (Ladakh), Manali Civil Hospital, IGMC Shimla, AIIMS Rishikesh, SKIMS Srinagar.
  - **National Apex Hospitals**: AIIMS New Delhi, Safdarjung Hospital, KEM Hospital Mumbai, Victoria Hospital Bengaluru, SSKM Hospital Kolkata, Rajiv Gandhi Hospital Chennai, GMCH Guwahati.
  - **National Disaster Response Force (NDRF)**: 8th Bn Ghaziabad (NCR), 5th Bn Pune, 10th Bn Guntur, 2nd Bn Kolkata, 1st Bn Guwahati.
  - **Relief & Cyclone Shelters**: Red Cross Disaster Relief Centres (Chennai, Bhubaneswar, Delhi), Coast Guard Maritime Rescue HQs (Mumbai, Port Blair).
- Fast offline search by name, city, or category, with live proximity sorting from the user's current GPS position.

### 7. Opt-In Signed Peer Location Sharing
- Users can choose to broadcast their location to nearby mesh peers (`MessageType.PEER_LOCATION` [0x32]).
- **Strictly Opt-In**: Disabled by default.
- **Smart Thresholding**: Only broadcasts when moving > 50 meters or every 5 minutes, preserving battery and radio bandwidth.
- Displays peer locations as pins on the offline map with distance and battery level.

### 8. Privacy-Preserving Notifications
- Foreground service notification alerts users of active emergency broadcasts while strictly suppressing GPS coordinates and message text to prevent lockscreen leakage.

---

## Offline Vector Map Setup

To use high-resolution local offline vector maps in GABRIEL:

1. Download or extract an OpenStreetMap PMTiles extract for your region (e.g., India) from [Protomaps](https://protomaps.com/extracts):
   ```bash
   # Example using pmtiles CLI:
   pmtiles extract https://build.protomaps.com/20261025.pmtiles india.pmtiles --bbox=68.1,6.7,97.4,35.7
   ```
2. Push the `.pmtiles` file directly to GABRIEL's offline map directory on your Android device:
   ```bash
   adb push india.pmtiles /storage/emulated/0/Android/data/com.gabriel.droid/files/maps/
   ```
3. Open or switch to the **Map** tab in GABRIEL. The app automatically detects `india.pmtiles` and switches from the baseline fallback map to full offline vector tiles.

---

## Build & Test Instructions

### Toolchain Requirements
- OpenJDK 21
- Android SDK (API 34 / compileSdk 37, minSdk 26)

### Build Commands

```bash
# Compile Kotlin sources
./gradlew :app:compileDebugKotlin

# Run all GABRIEL unit tests
./gradlew :app:testDebugUnitTest

# Assemble Debug APKs
./gradlew :app:assembleDebug
```

On Windows PowerShell:
```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Compiled APKs will be located at:
`app/build/outputs/apk/debug/`

---

## Crucial Safety & Threat Disclaimers

1. **NO GUARANTEE OF RESCUE**: GABRIEL is an ad-hoc offline peer-to-peer mesh system. Packet propagation depends entirely on physical peer density, line-of-sight RF conditions, and device battery levels. It is NOT directly connected to government emergency dispatch (such as 112) unless a physical user with radio or cellular access relays your call.
2. **PUBLIC SOS BEACON**: SOS broadcasts are cryptographically signed for authenticity but are **unencrypted over the air**. Anyone within radio range can decode your coordinates, battery percentage, and emergency note. Do not include sensitive credentials or personal information in SOS notes.
3. **TRUTHFUL ACKNOWLEDGEMENTS**: An acknowledgement (`SOS_ACK`) confirms only that another individual's device received the packet and the user tapped Acknowledge. It does NOT guarantee that official search and rescue teams are en route.
4. **BATTERY OPTIMIZATIONS**: Aggressive OEM battery killers (MIUI, OneUI, ColorOS) may terminate background Bluetooth scanning unless battery optimization is disabled. Ensure GABRIEL is exempt from battery saver restrictions before entering remote areas.
