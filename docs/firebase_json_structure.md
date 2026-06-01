# Estrutura JSON - Firebase (V2N - Emergency Alert)

## Origem
`app/src/main/java/com/bitchat/android/online/EmergencyAlert.kt:70-89`

## Estrutura do EmergencyAlert JSON

```json
{
  "message_id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "emergency",
  "vehicle": "ambulance",
  "lat": -23.5505,
  "lon": -46.6333,
  "speed": 45,
  "heading": 90,
  "timestamp": "2024-01-15T10:30:00Z",
  "ttl": 7,
  "geohash": "6gy5h6",
  "signature": "base64_encoded_signature_here"
}
```

## Campos

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `message_id` | String (UUID) | Identificador único da mensagem para deduplicação |
| `type` | String | Tipo de alerta: `emergency`, `approaching`, `passing`, `clearing` |
| `vehicle` | String | Tipo do veículo: `ambulance`, `police`, `fire_truck`, `rescue`, `other` |
| `lat` | Double | Latitude atual |
| `lon` | Double | Longitude atual |
| `speed` | Int | Velocidade em km/h |
| `heading` | Int | Direção em graus (0-360, 0=Norte) |
| `timestamp` | String (ISO 8601) | Timestamp da medição |
| `ttl` | Int | Time-to-live em hops (para roteamento mesh) |
| `geohash` | String (opcional) | Geohash para roteamento geográfico |
| `signature` | String (opcional) | Assinatura Ed25519 em base64 |

## Transmissão via Firebase

**Origem:** `app/src/main/java/com/bitchat/android/online/FirebaseTransport.kt:125-130`

O JSON é convertido para bytes e envelopado na estrutura do Firebase:

```kotlin
val messageData = mapOf(
    "data" to base64Data,        // Payload binário codificado em base64
    "ts" to System.currentTimeMillis(),  // Timestamp do servidor Firebase
    "ttl" to DEFAULT_TTL_SECONDS,         // 300 segundos
    "sender" to (auth.currentUser?.uid ?: "unknown")
)
```

## Caminho no Firebase Realtime Database

```
relay/
├── emergency/
│   └── {messageId}/
│       ├── data: "base64_encoded_payload"
│       ├── ts: 1705312200000
│       ├── ttl: 300
│       └── sender: "firebase_uid"
├── {geohash}/
│   └── {messageId}/
│       └── ...
└── test/
    └── {messageId}/
        └── ...
```

## Freshness Window

**Origem:** `FirebaseTransport.kt:33`

```
FRESHNESS_WINDOW_MS = 60_000L  // 60 segundos
```

Mensagens mais antigas que 60 segundos são descartadas para evitar replay de alertas históricos.
