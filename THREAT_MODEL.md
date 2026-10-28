# GABRIEL Security & Threat Model Specification

## 1. System Overview & Trust Assumptions

GABRIEL is an offline-first emergency mesh communication, GPS, and offline-map application designed for disaster relief, remote trekking, and network outage scenarios in India.

### Core Architecture
- **Transport**: Bluetooth Low Energy (BLE) GATT mesh with multi-hop packet relay (TTL-bounded) and Wi-Fi Aware direct links.
- **Identity & Crypto**: Ed25519 public keys for persistent identity and packet signing; Noise Protocol (`Noise_XX_25519_ChaChaPoly_BLAKE2s`) for mutual peer authentication and end-to-end encrypted direct messaging.
- **Offline Enforcement**: `android.permission.INTERNET` is completely removed from `AndroidManifest.xml`. All Tor, Nostr remote internet relays, and remote telemetry are disabled at compile time.
- **Emergency SOS Subsystem**: Public, signed broadcast beacon containing verified platform GPS coordinates, fix quality, fix age, emergency type, battery percentage, and an optional 140-byte note.
- **Safe Locations & POIs**: 100% offline bundled database of emergency medical trauma centres, police headquarters, NDRF bases, and disaster shelters across India.

---

## 2. Threat Analysis & Defensive Mitigations

### Threat 1: SOS Forgery, Identity Impersonation & Coordinate Spoofing
- **Vector**: A malicious actor broadcasts crafted emergency packets pretending to be another peer, attempting to create panic, redirect search teams, or forge SOS distress beacons.
- **Defenses**:
  1. **Mandatory Ed25519 Signature Enforcement**: `SecurityManager` strictly requires valid Ed25519 signatures for all `MessageType.SOS`, `MessageType.SOS_ACK`, and `MessageType.PEER_LOCATION` packets. Unsigned or invalidly signed packets are rejected immediately before reaching user-visible state or relay channels.
  2. **Canonical Preimage Binding**: Signatures cover the canonical binary representation (`toBinaryDataForSigning()`), locking all payload bytes (coordinates, timestamp, emergency type, battery, message note, sender ID). Tampering with a single bit or coordinate invalidates the cryptographic signature.
  3. **Identity Key Binding**: The signing key is validated against verified identity announcements, preventing identity theft across the mesh.

### Threat 2: Replay Attacks on Emergency State
- **Vector**: An attacker records a previously broadcast `ACTIVE` SOS beacon and re-transmits it hours or days later after the emergency has ended, or re-broadcasts an old `ACTIVE` beacon after the victim has broadcast `RESOLVED` or `CANCELLED`.
- **Defenses**:
  1. **Time-Window Bounding**: `SosInbox` drops any packet with a timestamp older than 24 hours (`System.currentTimeMillis() - timestamp > 86,400,000 ms`) or skewed into the future by more than 5 minutes (`timestamp - now > 300,000 ms`).
  2. **Terminal State Permanence**: Once an event enters `WireStatus.RESOLVED` or `WireStatus.CANCELLED`, it can never transition back to `ACTIVE`. Replayed active packets for an already terminated event are permanently ignored.
  3. **Sender-Side Forward-Only State Machine**: The sender's `SosEvent` transitions strictly forward: `CREATED -> QUEUED -> RELAYED -> ACKNOWLEDGED / CANCELLED / RESOLVED`. Terminal states reject cancellation, resolution, or new acknowledgements.

### Threat 3: BLE Mesh Flooding & Emergency Packet Starvation (DoS)
- **Vector**: A compromised node or high-volume user floods the BLE mesh with large binary file chunks, voice notes, or rapid chat messages, filling the radio queue and delaying life-critical SOS beacons.
- **Defenses**:
  1. **Dual-Channel Outbound Dispatch**: `BluetoothPacketBroadcaster` implements two distinct channels: an unbounded `emergencyBroadcastChannel` and a bounded `normalBroadcastChannel`. The broadcast worker always drains emergency packets before checking normal traffic.
  2. **Per-Link Priority Queueing**: For active GATT connections, `LinkSendState` maintains separate `emergencyPending` and `normalPending` queues. Emergency packets (`SOS`, `SOS_ACK`) are always dequeued first.
  3. **Normal Packet Eviction**: When a link queue reaches saturation, normal packets are dropped to admit inbound emergency packets.
  4. **Bounded Deduplication Capacity**: `SosInbox` is hard-bounded to 50 active events using an LRU eviction strategy to prevent memory exhaustion on resource-constrained devices.

### Threat 4: False Delivery Guarantees & Social Engineering
- **Vector**: Users assume pressing SOS guarantees rescue, or interpret an acknowledgement (`SOS_ACK`) as confirmation that government emergency services or NDRF have dispatched a rescue helicopter.
- **Defenses**:
  1. **Truthful Delivery States**: The app UI never claims "Delivered". It displays strictly verified delivery stages:
     - `CREATED`: Beacon initialized locally.
     - `QUEUED`: Enqueued in outbound BLE priority queue.
     - `RELAYED`: Successfully handed over to at least one physical BLE peer connection.
     - `ACKNOWLEDGED`: Cryptographically verified `SOS_ACK` received from a specific named peer.
     - `RESOLVED` / `CANCELLED`: Beacon explicitly closed by the originator.
  2. **Persistent Honest Disclaimers**: Displayed prominently on the SOS screen and About screen:
     - *"NO GUARANTEE OF RESCUE: GABRIEL is an ad-hoc mesh experiment. Delivery depends entirely on nearby peer radios."*
     - *"AN ACKNOWLEDGEMENT IS FROM AN INDIVIDUAL USER, NOT OFFICIAL POLICE OR SEARCH & RESCUE DISPATCH."*
     - *"GPS ACCURACY IS SUBJECT TO ENVIRONMENTAL OBSTRUCTIONS (canyons, dense tree cover, indoor spaces)."*

### Threat 5: Passive Radio Eavesdropping & Location Disclosure
- **Vector**: Radio receivers within Bluetooth range (30-100m) monitor raw packets over the air.
- **Characteristics & Defenses**:
  1. **Public SOS vs Private Chat**: SOS beacons are **public by design**. Emergency responders cannot help if they cannot decrypt the coordinates. Users are warned via an explicit two-step modal before transmission:
     - *"PUBLIC BROADCAST: Anyone with a compatible radio in Bluetooth range can read your coordinates, battery status, and note."*
  2. **Opt-in Peer Location Sharing**: Non-emergency peer location sharing is **disabled by default (opt-in only)** and requires explicit user activation. Updates are rate-limited to only when the user moves > 50 meters or every 5 minutes.
  3. **Notification Privacy**: Android notifications strictly **suppress GPS coordinates and message notes**. Notifications display only `GABRIEL [EMERGENCY SOS ACTIVE]` or `EMERGENCY SOS: MEDICAL Alert from peer ...`, preventing coordinate scraping by lockscreen observers or unauthorized notification listeners.
  4. **Zero Diagnostic Network Leakage**: The app binary contains no analytics, crash reporters, telemetry, or remote advertising SDKs.

### Threat 6: Background Process Killing by Aggressive OEM Battery Optimizers
- **Vector**: Aggressive Android vendor battery management (Samsung, Xiaomi, Oppo, Vivo) terminates background services, preventing the device from relaying emergency beacons or receiving SOS alerts when the screen is turned off.
- **Defenses**:
  1. **Persistent Foreground Service**: `MeshForegroundService` runs as a high-priority foreground service with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` and `FOREGROUND_SERVICE_TYPE_LOCATION`.
  2. **Emergency Notification Escalation**: When an SOS is actively broadcasting, the persistent notification elevates to `PRIORITY_HIGH` and `CATEGORY_ALARM`.
  3. **Battery Optimization Whitelist Guidance**: In-app onboarding prompts users to disable battery optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) so the radio stack remains alive during long treks or disasters.

---

## 3. Cryptographic Primitives Summary

| Purpose | Primitive | Implementation | Key Size / Spec |
| :--- | :--- | :--- | :--- |
| **Identity & Packet Signing** | Ed25519 | Bouncy Castle `Ed25519Signer` | 256-bit public key, 512-bit signature |
| **Transport Key Exchange** | X25519 ECDH | Noise Protocol (`Noise_XX`) | 256-bit curve keys |
| **Authenticated Encryption** | ChaCha20-Poly1305 | Noise AEAD | 256-bit key, 128-bit MAC tag |
| **Hashing & HKDF** | BLAKE2s / SHA-256 | Bouncy Castle / Java Security | 256-bit digest |
| **Deduplication / IDs** | SecureRandom | `java.security.SecureRandom` | 128-bit CSPRNG Event IDs |

---

## 4. Hardware & Physics Boundaries

1. **RF Propagation Limits**: BLE operates in the 2.4 GHz ISM band. Signal range is typically 30-100 meters outdoors in line-of-sight and 10-30 meters indoors or in dense foliage. Mountains, rock faces, and concrete walls attenuate RF signals significantly.
2. **Hop Latency & Partitioning**: Multi-hop relay depends on the spatial continuity of devices. A partitioned mesh cannot transmit packets across physical gaps wider than radio range.
3. **GPS Fix Vulnerabilities**: GPS satellite signals are weak (-130 dBm). In deep Himalayan gorges, caves, dense tree canopy, or urban canyons, GPS fixes may become inaccurate or unavailable (`NO_FIX`). GABRIEL always records and broadcasts horizontal accuracy (meters) and fix age (seconds) to prevent reliance on stale coordinates.
