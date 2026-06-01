# Estrutura TLV - Protocolo Mesh Binário

## Origem
`app/src/main/java/com/bitchat/android/protocol/BinaryProtocol.kt`

---

## Estrutura Completa do Pacote Binário

```
┌─────────────────────────────────────────────────────────────┐
│                         HEADER (13 ou 15 bytes)              │
├─────────┬─────────┬─────────┬────────────────┬────────┬────────┤
│ Version │  Type   │   TTL   │   Timestamp    │ Flags  │PayLen  │
│  1 byte │  1 byte │  1 byte │    8 bytes    │ 1 byte │2 or 4B │
└─────────┴─────────┴─────────┴────────────────┴────────┴────────┘

┌──────────────────────────────────────────────────────────────────┐
│                          CORPO VARIÁVEL                          │
├──────────────┬──────────────┬───────────────┬──────────┬───────────┤
│  SenderID    │ RecipientID  │    Route      │ Payload  │ Signature │
│   8 bytes    │  0 or 8 bytes│  (se v2+)    │  N bytes │ 64 bytes  │
└──────────────┴──────────────┴───────────────┴──────────┴───────────┘
                (se existir)   1+N*8 bytes
```

---

## Header Detalhado

### Bytes 0: Version
```
 bits 7-0: Version number (1 ou 2)
```

### Byte 1: Type (MessageType)
```
 0x01 = ANNOUNCE          (descoberta de peers)
 0x02 = MESSAGE           (mensagens de usuário)
 0x03 = LEAVE             (desconexão)
 0x10 = NOISE_HANDSHAKE   (handshake Noise)
 0x11 = NOISE_ENCRYPTED   (mensagem criptografada)
 0x20 = FRAGMENT          (fragmentação de pacotes grandes)
 0x21 = REQUEST_SYNC      (solicitação de sincronização GCS)
 0x22 = FILE_TRANSFER     (transferência de arquivos)
 0x30 = EMERGENCY_ALERT   (alertas V2V de emergência)
```

### Byte 2: TTL
```
 bits 7-0: Time-to-live em hops (0-255)
 Valor máximo: MESSAGE_TTL_HOPS = 7
```

### Bytes 3-10: Timestamp
```
 8 bytes, UInt64, Big-Endian
 Representa milliseconds desde epoch
```

### Byte 11: Flags
```
 bit 0 (0x01): HAS_RECIPIENT    - recipientID presente
 bit 1 (0x02): HAS_SIGNATURE     - signature presente
 bit 2 (0x04): IS_COMPRESSED    - payload está comprimido
 bit 3 (0x08): HAS_ROUTE        - route presente (apenas v2+)
```

### Bytes 12-15: Payload Length (varia conforme versão)
```
 v1: 2 bytes (UInt16, Big-Endian) - máximo 65535 bytes
 v2: 4 bytes (UInt32, Big-Endian) - máximo ~4GB
```

---

## Corpo Variável

### SenderID (8 bytes fixos)
```
 Identificador do remetente (8 bytes)
 Formato: primeiros 8 bytes do peer ID (hex)
```

### RecipientID (0 ou 8 bytes)
```
 Presente apenas se Flags.HAS_RECIPIENT = 1
 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF = Broadcast
```

### Route (opcional, v2+)
```
 1 byte: count (número de hops)
 N*8 bytes: lista de peerIDs intermediários
```

### Payload (N bytes)
```
 Dados da mensagem em formato binário
 Se IS_COMPRESSED: [original_size (2 ou 4 bytes)][compressed_data]
```

### Signature (64 bytes, opcional)
```
 Presente apenas se Flags.HAS_SIGNATURE = 1
 Assinatura Ed25519 de todo o pacote (exceto campo signature)
```

---

## TLV Internos - NoisePayload

Para mensagens privadas criptografadas, o payload contém um wrapper NoisePayload:

### Estrutura
```
┌────────────┬─────────────┐
│    Type    │    Data     │
│  1 byte    │  N bytes    │
└────────────┴─────────────┘
```

### Type Bytes
```
 0x01 = PRIVATE_MESSAGE   (mensagem privada)
 0x02 = FILE_TRANSFER    (arquivo)
 0x03 = READ_RECEIPT     (recibo de leitura)
 0x04 = VERIFY_CHALLENGE (desafio de verificação)
 0x05 = VERIFY_RESPONSE  (resposta de verificação)
```

---

## Exemplo - Mensagem de Texto Broadcast

**Bytes hexadecimais:**
```
01 02 07 00 00 00 00 00 65 9F 41 5C 00 00 00 00 00 00 00 00 00 00
│ │ │ └─────────────── Timestamp (8 bytes) ───────────────┘
│ │ └─ TTL = 7 hops
│ └─ Type = 0x02 (MESSAGE)
└─ Version = 1

00 (Flags: sem recipient, sem signature)
00 0D (Payload length = 13 bytes)

A3 B4 C5 D6 E7 F8 90 12 (SenderID = 8 bytes)

48 65 6C 6C 6F 20 57 6F 72 6C 64 21 0A (Payload: "Hello World!\n")

[Padding opcional para resistência a análise de tráfego]
```

---

## Exemplo - Emergency Alert

```
01 30 07 00 00 65 9F 41 5C 00 00 00 00 00 00 00 00 00 00 ...
│ │ │ └─────────────── Timestamp ───────────────┘
│ │ └─ TTL = 7 hops
│ └─ Type = 0x30 (EMERGENCY_ALERT)
└─ Version = 1
```

---

## Padding para Resistência a Análise de Tráfego

**Origem:** `BinaryProtocol.kt:310-314`

O pacote é preenchido (padded) para tamanhos de bloco padrão:

```kotlin
val optimalSize = MessagePadding.optimalBlockSize(result.size)
val paddedData = MessagePadding.pad(result, optimalSize)
```

---

## Comparativo Firebase vs Mesh

| Aspecto | Firebase (V2N) | Mesh (BLE) |
|---------|----------------|------------|
| **Formato** | JSON | Binário TLV |
| **Encoding** | Base64 | Raw binary |
| **Header overhead** | ~200+ bytes | 13-15 bytes |
| **Payload** | JSON string | Binário |
| **Compressão** | Não | Opcional (deflate) |
| **TTL** | 300 segundos | 7 hops |
| **Uso** | Emergências cross-network | Mensagens P2P locais |
