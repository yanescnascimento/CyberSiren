# V2V (Alerta Veicular) — iOS port

Swift port of the Android `com.bitchat.android.v2v` module. Adds emergency-vehicle
broadcasting and receiving over BLE mesh + Firebase cloud, with a SwiftUI screen
and a CarPlay scene.

## Layout

```
V2V/
├── Models/          # Domain models. Wire-format compatible with Android.
├── Services/        # Backend: deduper, transport log, emergency service,
│                    # Firebase transport, NWPathMonitor, orchestrator.
├── UI/              # SwiftUI screens, ViewModel, locale + strings + colors.
├── CarPlay/         # CPTemplateApplicationSceneDelegate analogue of AA.
└── Integration/     # Glue points the host app fills in (BLE adapter).
```

## Wiring checklist

1. **Add files to the Xcode target.** All sources under `V2V/` are pure Swift —
   drop them into the `bitchat` target and they'll compile.

2. **Protocol bump.** `MessageType.emergencyAlert = 0x30` was added to
   `BitFoundation/MessageType.swift`. Make sure BLEService dispatches packets of
   that type into `V2VInboundDispatcher.dispatch(...)`.

3. **BLE outbound hook.** Construct a `V2VBitchatMeshAdapter` that knows how to
   shape a `BitchatPacket(type: .emergencyAlert, payload: jsonBytes, ttl: 7)` and
   push it through the existing mesh broadcaster. Pass that adapter to
   `V2VViewModel`.

4. **Firebase (optional).** Add `firebase-ios-sdk` via Swift Package Manager
   (Auth + Database) and bundle `GoogleService-Info.plist` for project `humedu`.
   Without those frameworks, `FirebaseTransport` reports `isAvailable = false`
   and the system runs BLE-only.

5. **CarPlay scene.**

   - Add the `com.apple.developer.carplay-communication` entitlement.
   - Register a `CPTemplateApplicationScene` configuration in `Info.plist` whose
     delegate class is `V2VCarSceneDelegate` (see the file's header for the
     plist snippet).
   - In the SwiftUI app entry point, retain a `V2VCarAdapter(viewModel:)` and
     call `V2VCarServiceHolder.shared.setService(adapter)` once the V2V
     ViewModel is ready.

6. **Notifications.** Call `V2VCarNotifier.shared.ensureCategory()` and request
   `.alert`, `.sound`, and `.criticalAlert` authorization at app launch so heads-up
   alerts surface on CarPlay.

7. **Permissions.** Add to `Info.plist`:
   - `NSLocationWhenInUseUsageDescription` (continuous GPS for senders).
   - `NSLocationAlwaysAndWhenInUseUsageDescription` (background broadcast).
   - `UIBackgroundModes` already lists `bluetooth-central`/`bluetooth-peripheral`;
     add `location` if you want senders to keep broadcasting from the background.

8. **Localization.** `V2VStrings` reads `Localizable.strings` keys
   (`v2v_vehicle_*`, `v2v_car_*`, `v2v_notif_*`, `v2v_settings_*`, ...). Mirror
   the keys already present in Android `values/`, `values-en/`, `values-es/`,
   `values-pt-rBR/`.

## Cross-platform wire format

| Channel | Path                                | Format |
|---------|-------------------------------------|--------|
| BLE     | `BitchatPacket.payload` (type 0x30) | JSON: `{id, vt, at, lat, lon, spd, hdg, ts, pid}` |
| Cloud   | `relay/<geohash>/<messageId>`       | `{ data: base64(JSON), ts, ttl, sender }` where the inner JSON is `{message_id, type, vehicle, lat, lon, speed, heading, timestamp, ttl}` |

Keep both formats untouched — Android peers will silently drop packets that
don't decode.
