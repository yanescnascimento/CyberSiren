
# CyberSiren

CyberSiren is a hybrid emergency alert system designed to improve emergency vehicle awareness for Vulnerable Road Users (VRUs), including pedestrians, cyclists, motorcyclists, and people with reduced mobility.

The system combines cloud-based communication with Bluetooth Low Energy (BLE) mesh propagation, enabling emergency alerts to be delivered through redundant communication channels. This architecture aims to reduce the risk of alert loss when cellular connectivity is delayed or unavailable.

The project was developed as part of the research presented in:

**CyberSiren: A Hybrid Emergency Alert System for Vulnerable Road Users**
International Smart Cities Conference (ISC2), 2025.

---

## Research Contributions

This repository provides:

* The implementation of CyberSiren, a hybrid emergency alert system based on cloud communication and BLE mesh networking.
* Support for emergency alert dissemination across smartphones, wearables, and vehicle infotainment systems.
* A redundant communication mechanism designed to improve alert delivery continuity.
* A proof-of-concept implementation evaluated in a real deployment environment using commercial devices.
* Experimental artifacts that complement the simulation-oriented validation commonly found in V2X research.

---

## Features

* Emergency vehicle alert broadcasting
* Cloud-based alert dissemination using Firebase
* BLE Mesh local propagation
* Redundant multi-channel communication
* Alert deduplication using UUID validation
* Smartphone integration (Android and iOS)
* Smartwatch notifications
* Vehicle infotainment support
* Crowdsourced hazard reporting

---

## System Architecture

CyberSiren consists of three main components:

### 1. Transmitter

The transmitter runs on an emergency vehicle device and collects:

* GPS coordinates
* Speed
* Heading
* Vehicle type
* Timestamp

Alert messages are simultaneously transmitted through:

* Firebase Realtime Database
* BLE Mesh network

---

### 2. Hybrid Communication Layer

The communication layer combines:

#### Cloud Channel

* Firebase Realtime Database
* Geohash-based spatial partitioning
* Publish-subscribe communication model

#### BLE Mesh Channel

* Based on the BitChat protocol
* Multi-hop local propagation
* Infrastructure-independent operation

Receivers process the first valid alert received and discard duplicates.

---

### 3. Receiver

Receivers may include:

* Smartphones
* Smartwatches
* Vehicle infotainment systems

The receiver validates:

* Digital signatures
* UUID uniqueness
* Timestamp validity
* Alert expiration (TTL)

After validation, alerts are presented through visual and haptic notifications.

---

## Experimental Validation

The proof-of-concept evaluation was conducted at the State University of Feira de Santana (UEFS), Brazil.

Experimental setup:

* 1 emergency vehicle node
* 1 vehicle infotainment receiver
* 1 cyclist receiver
* 1 pedestrian receiver
* 1 motorcyclist receiver

Devices used:

* Xiaomi Redmi Note 14 Pro 5G
* Samsung Galaxy S24 Ultra
* Samsung Galaxy Watch

Results reported in the paper:

| Metric             | BLE Mesh | Cellular | Hybrid |
| ------------------ | -------- | -------- | ------ |
| Messages Sent      | 544      | 544      | 544    |
| Messages Delivered | 544      | 404      | 544    |
| Delivery Rate      | 100.0%   | 74.3%    | 100.0% |

---

## Repository Structure

```text
CyberSiren/
│
├── android/
├── ios/
├── wearable/
├── backend/
├── docs/
├── experiments/
├── datasets/
└── README.md
```

---

## Requirements

### Mobile

* Android 10+
* iOS 16+
* Bluetooth Low Energy (BLE)
* Internet connectivity

### Backend

* Firebase Realtime Database
* Firebase Authentication

---

## Building the Project

### Android

```bash
git clone https://github.com/yan/CyberSiren.git
cd CyberSiren/android
```

Open the project in Android Studio and run:

```bash
./gradlew assembleDebug
```

### iOS

```bash
cd CyberSiren/ios
```

Open the project using Xcode and build normally.

---

## Reproducing the Experiments

1. Configure Firebase credentials.
2. Enable BLE permissions.
3. Deploy one transmitter node.
4. Deploy multiple receiver nodes.
5. Start emergency alert broadcasts.
6. Collect delivery logs.
7. Analyze delivery rate, packet loss, and latency.

---

## License

This project is released for academic and research purposes.

Please contact the authors for commercial use permissions.


>Notice and Credits

>This V2V alert application is based on bitchat, originally developed as a peer-to-peer messaging platform. The project reuses and extends bitchat's Bluetooth mesh networking infrastructure and underlying protocol to support reliable, real-time communication for vehicle-to-vehicle emergency alert dissemination.