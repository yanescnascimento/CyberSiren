# Firebase Setup Guide - Bitchat V2X Hybrid Transport

Este documento explica como configurar o Firebase Realtime Database para o sistema híbrido V2V/V2N de alertas de emergência veicular.

## Arquitetura de Segurança

```
┌─────────────────────────────────────────────────────────────────┐
│                    PRINCÍPIO FUNDAMENTAL                        │
│                                                                 │
│   Firebase = "Relay Burro" (Dumb Pipe)                         │
│   - NÃO descriptografa mensagens                                │
│   - NÃO valida conteúdo                                         │
│   - NÃO é autoridade central                                    │
│   - APENAS retransmite bytes criptografados                     │
└─────────────────────────────────────────────────────────────────┘
```

## 1. Criar Projeto Firebase

### Passo 1: Console Firebase
1. Acesse [Firebase Console](https://console.firebase.google.com/)
2. Clique em **"Criar projeto"** ou **"Add project"**
3. Nome do projeto: `bitchat-v2x` (ou outro nome)
4. Desabilite Google Analytics (opcional para privacidade)
5. Clique em **"Criar projeto"**

### Passo 2: Adicionar App Android
1. No console, clique no ícone Android
2. Preencha:
   - **Package name**: `com.bitchat.droid`
   - **App nickname**: `Bitchat V2X`
   - **SHA-1**: (opcional, para auth avançado)
3. Clique em **"Registrar app"**

### Passo 3: Download do google-services.json
1. Baixe o arquivo `google-services.json`
2. Coloque em: `app/google-services.json`

```
bitchat-android-main/
├── app/
│   ├── google-services.json   ← AQUI
│   ├── build.gradle.kts
│   └── src/
```

## 2. Configurar Realtime Database

### Passo 1: Criar Database
1. No console Firebase, vá em **Build → Realtime Database**
2. Clique em **"Create Database"**
3. Escolha a região mais próxima (ex: `us-central1` ou `southamerica-east1`)
4. Selecione **"Start in locked mode"** (vamos configurar regras depois)

### Passo 2: Obter URL do Database
Após criar, você verá a URL do banco:
```
https://bitchat-v2x-default-rtdb.firebaseio.com/
```

Guarde esta URL - será usada na configuração do app.

## 3. Configurar Security Rules

### Regras Recomendadas (Seguras)

No console Firebase, vá em **Realtime Database → Rules** e cole:

```json
{
  "rules": {
    // Canal de relay para mensagens por geohash
    "relay": {
      "$geohash": {
        // Qualquer um pode ler (mensagens são criptografadas)
        ".read": true,

        // Apenas usuários autenticados podem escrever
        ".write": "auth != null",

        "$messageId": {
          // Validação: deve ser string Base64, max 4KB
          ".validate": "newData.hasChildren(['data', 'ts']) &&
                        newData.child('data').isString() &&
                        newData.child('data').val().length < 5500 &&
                        newData.child('ts').isNumber()"
        }
      }
    },

    // Inbox para mensagens diretas
    "inbox": {
      "$peerIdHash": {
        ".read": true,
        ".write": "auth != null",

        "$messageId": {
          ".validate": "newData.hasChildren(['data', 'ts']) &&
                        newData.child('data').isString() &&
                        newData.child('data').val().length < 5500"
        }
      }
    },

    // Canal de emergência (prioridade alta)
    "emergency": {
      ".read": true,
      ".write": "auth != null,

      "$alertId": {
        ".validate": "newData.hasChildren(['data', 'ts']) &&
                      newData.child('data').isString()"
      }
    },

    // Bloquear todo o resto
    ".read": false,
    ".write": false
  }
}
```

### Regras Simplificadas (Para Testes)

Para desenvolvimento rápido (NÃO use em produção):

```json
{
  "rules": {
    "relay": {
      ".read": true,
      ".write": true
    },
    "inbox": {
      ".read": true,
      ".write": true
    },
    "emergency": {
      ".read": true,
      ".write": true
    }
  }
}
```

## 4. Habilitar Autenticação Anônima

### Passo 1: Ativar Provider
1. No console Firebase, vá em **Build → Authentication**
2. Clique em **"Get started"**
3. Na aba **Sign-in method**, clique em **"Anonymous"**
4. Habilite o toggle e clique em **"Save"**

### Por que Autenticação Anônima?
- ✅ Não cria identidade real (preserva privacidade)
- ✅ Previne spam trivial
- ✅ Permite usar Security Rules com `auth != null`
- ✅ Não requer email/senha do usuário

## 5. Configurar no App Android

### Opção A: Via google-services.json (Recomendado)

1. Coloque `google-services.json` em `app/`
2. O Firebase SDK lê automaticamente as configurações

### Opção B: Configuração Manual (Sem SDK completo)

Se preferir não usar o SDK completo do Firebase, configure manualmente:

```kotlin
// Em Application.onCreate() ou MainActivity.onCreate()
class BitchatApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configurar Firebase Transport
        FirebaseTransport.configure(
            databaseUrl = "https://SEU-PROJETO.firebaseio.com"
        )
    }
}
```

## 6. Adicionar Dependências (Se usar SDK Firebase)

### build.gradle.kts (projeto raiz)
```kotlin
plugins {
    // ... outros plugins
    id("com.google.gms.google-services") version "4.4.0" apply false
}
```

### app/build.gradle.kts
```kotlin
plugins {
    // ... outros plugins
    id("com.google.gms.google-services")
}

dependencies {
    // Firebase BoM (Bill of Materials)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))

    // Realtime Database
    implementation("com.google.firebase:firebase-database-ktx")

    // Auth (para autenticação anônima)
    implementation("com.google.firebase:firebase-auth-ktx")
}
```

## 7. Estrutura do Banco de Dados

```
Firebase Realtime Database
│
├── relay/                          # Mensagens por área geográfica
│   ├── {geohash-6}/               # Ex: "6gkzwg" (São Paulo)
│   │   ├── {uuid-1}/
│   │   │   ├── data: "base64..."  # Pacote criptografado
│   │   │   ├── ts: 1706000000000  # Timestamp Unix ms
│   │   │   └── ttl: 300           # Segundos até expirar
│   │   └── {uuid-2}/
│   │       └── ...
│   │
│   └── emergency/                  # Canal de emergência global
│       └── {uuid}/
│           └── ...
│
└── inbox/                          # Mensagens diretas
    └── {peer-id-hash}/            # SHA-256 do peer ID
        └── {uuid}/
            ├── data: "base64..."
            └── ts: 1706000000000
```

## 8. Limpeza Automática (Cloud Functions)

Para limpar mensagens expiradas automaticamente, crie uma Cloud Function:

```javascript
// functions/index.js
const functions = require('firebase-functions');
const admin = require('firebase-admin');
admin.initializeApp();

// Executar a cada hora
exports.cleanupExpiredMessages = functions.pubsub
  .schedule('every 1 hours')
  .onRun(async (context) => {
    const db = admin.database();
    const now = Date.now();
    const maxAge = 5 * 60 * 1000; // 5 minutos

    // Limpar relay
    const relayRef = db.ref('relay');
    const relaySnapshot = await relayRef.once('value');

    relaySnapshot.forEach((geohashSnap) => {
      geohashSnap.forEach((msgSnap) => {
        const ts = msgSnap.child('ts').val();
        if (now - ts > maxAge) {
          msgSnap.ref.remove();
        }
      });
    });

    // Limpar inbox
    const inboxRef = db.ref('inbox');
    const inboxSnapshot = await inboxRef.once('value');

    inboxSnapshot.forEach((peerSnap) => {
      peerSnap.forEach((msgSnap) => {
        const ts = msgSnap.child('ts').val();
        if (now - ts > maxAge) {
          msgSnap.ref.remove();
        }
      });
    });

    console.log('Cleanup completed');
    return null;
  });
```

## 9. Verificação da Configuração

### Checklist

- [ ] Projeto Firebase criado
- [ ] Realtime Database criado
- [ ] `google-services.json` em `app/`
- [ ] Security Rules configuradas
- [ ] Autenticação Anônima habilitada
- [ ] Dependências adicionadas ao `build.gradle.kts`
- [ ] `FirebaseTransport.configure()` chamado no app

### Teste Rápido

```kotlin
// Testar conexão
val transport = FirebaseTransport.getInstance(context)
if (transport.isAvailable) {
    Log.i("Test", "Firebase transport disponível!")
}
```

## 10. Segurança - Perguntas Frequentes

### A API Key do Firebase é segura?

**Sim.** A API key do Firebase é pública por design. O que protege seus dados são:
1. **Security Rules** - Definem quem pode ler/escrever
2. **Autenticação** - Valida usuários
3. **Criptografia E2E** - Feita no app, Firebase não vê conteúdo

### O Firebase pode ler minhas mensagens?

**Não**, se você criptografar no cliente (como o Bitchat faz). O Firebase vê apenas:
- Bytes em Base64
- Timestamps
- Geohashes (localização aproximada)

O conteúdo real está criptografado com chaves que o Firebase não possui.

### Posso usar sem Firebase Auth?

**Sim**, mas perde proteção contra spam. Recomendamos pelo menos auth anônima.

---

## Suporte

- [Firebase Documentation](https://firebase.google.com/docs/database)
- [Security Rules Guide](https://firebase.google.com/docs/database/security)
- [Android Quickstart](https://firebase.google.com/docs/database/android/start)
