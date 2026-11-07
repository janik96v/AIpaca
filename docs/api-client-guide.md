# firn API Client Guide

How to connect to and use the firn AI server from a laptop, another app, or any HTTP client.

---

## Overview

firn runs an **HTTPS server on port 8443** with an OpenAI-compatible API.
Every client must **pair once** before it can make API calls.

Requests are answered by whatever model the app has active: a local GGUF model, or — if Ollama is enabled in the chat Modes menu — the model selected on your local-network Ollama server.

```
Client                          firn (your phone)
──────                          ──────────────────────
Pair once   ──── /v1/pair ────► Register your public key
API calls   ── signed header ►  Verify signature → respond
```

Authentication uses **Ed25519 asymmetric keys** — the same principle as SSH.
Your private key never leaves your device. The phone stores only your public key.

---

## Step 1 — Start the server on your phone

1. Open firn → **MDL** and load a model (get one via **Browse models** or **Open a .gguf file**)
2. Open **SRV** and tap **Start server**

The SRV screen and the notification show the address (tap it on SRV to copy):
```
https://192.168.1.42:8443
```
The server listens only on the phone's WiFi/Ethernet address — not on hotspot or VPN interfaces.

---

## Step 2 — Generate a pairing PIN on your phone

1. On **SRV** tap **Pair a new device** (available while the server runs)
2. A QR code appears with a 6-digit PIN below it

The PIN is valid for 5 minutes and works once. Ten wrong attempts invalidate it; tap **New PIN** after expiry.

You can either:
- **Scan the QR code** with a client that supports it. It encodes
  `{"endpoint":"https://192.168.1.42:8443","pin":"482910","fingerprint":"AA:BB:…"}`
- **Use the PIN manually** by following Step 3 below

---

## Step 3 — Pair your client

### Python (recommended)

Use the scripts in [`examples/python/`](../examples/python/):
```bash
cd examples/python
pip install -r requirements.txt
python3 pair.py
# Server URL: https://192.168.1.42:8443
# PIN shown on phone: 482910
# Device name [My Mac]: My Laptop
```

`pair.py` generates an Ed25519 key pair (`~/.firn/client_key.pem`), downloads the server certificate (`~/.firn/server.pem`), checks it against the `serverCertFingerprint` returned by `/v1/pair`, and saves `~/.firn/config.json`.

### curl (manual pairing)

Needs OpenSSL 3 (macOS ships LibreSSL, which lacks Ed25519 — use `brew install openssl@3`).

```bash
mkdir -p ~/.firn
openssl genpkey -algorithm ed25519 -out ~/.firn/client_key.pem
PUBKEY=$(openssl pkey -in ~/.firn/client_key.pem -pubout -outform DER | tail -c 32 | base64 | tr -d '\n')

PHONE="https://192.168.1.42:8443"
PIN="482910"

curl -k -X POST "$PHONE/v1/pair" \
  -H "Content-Type: application/json" \
  -d "{\"clientPublicKey\":\"$PUBKEY\",\"pin\":\"$PIN\",\"displayName\":\"curl client\"}"
```

> **Note:** `-k` skips TLS verification for this one-time call. The response contains the server's cert fingerprint — use it to pin the cert in future requests (see [TLS](#tls--certificate-handling)).

---

## Step 4 — Make API calls

Every request after pairing must include a signed `Authorization` header.

### Header format

```
Authorization: Firn-Ed25519 <base64-pubkey> <base64-signature> <unix-timestamp>
```

- `<base64-pubkey>` — base64 of the raw 32-byte Ed25519 public key
- `<base64-signature>` — base64 Ed25519 signature over the **signed message**, which is exactly:
  ```
  Firn-Ed25519:<unix-timestamp-seconds>
  ```

The timestamp must be within **±30 seconds** of the phone's clock. The signature covers only the timestamp, not the request, so generate a fresh header per request and never send it outside TLS.

---

### Python

```python
import base64, time
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

def auth_header(private_key) -> str:
    pubkey = base64.b64encode(
        private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    ).decode()
    ts = str(int(time.time()))
    sig = base64.b64encode(private_key.sign(f"Firn-Ed25519:{ts}".encode())).decode()
    return f"Firn-Ed25519 {pubkey} {sig} {ts}"
```

[`examples/python/chat.py`](../examples/python/chat.py) wraps this into a complete client with cert pinning and streaming:
```bash
python3 chat.py "What is the capital of Switzerland?"
python3 chat.py "Tell me a short story" --stream
```

### curl (signed request)

```bash
KEY=~/.firn/client_key.pem
TIMESTAMP=$(date +%s)
printf '%s' "Firn-Ed25519:$TIMESTAMP" > /tmp/firn_msg
SIG=$(openssl pkeyutl -sign -inkey "$KEY" -rawin -in /tmp/firn_msg | base64 | tr -d '\n')
PUBKEY=$(openssl pkey -in "$KEY" -pubout -outform DER | tail -c 32 | base64 | tr -d '\n')

curl -k -X POST "https://192.168.1.42:8443/v1/chat/completions" \
  -H "Authorization: Firn-Ed25519 $PUBKEY $SIG $TIMESTAMP" \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"Hello!"}],"stream":false}'
```

The header expires after 30 seconds — rerun the first lines for each request.

---

## Connecting from another app on the same phone

Use the URL shown on **SRV** (the phone's own WiFi address). The server binds to that interface only, so `127.0.0.1` is not reachable while WiFi is connected.

The pairing flow is identical: tap **Pair a new device** on SRV, use the PIN in your app.

### Android app (Kotlin / OkHttp)

Ed25519 keys in Android Keystore need Android 13+.

Add OkHttp to your `build.gradle`:
```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

Key generation and signing:
```kotlin
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.*
import java.util.Base64

object FirnClient {

    private const val KEY_ALIAS = "firn_client"

    // Call once at app start — generates key in Android Keystore if not present
    fun ensureKeyExists() {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("Ed25519"))
                .setDigests(KeyProperties.DIGEST_NONE)
                .build()
            )
            generateKeyPair()
        }
    }

    fun getPublicKeyBase64(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val cert = ks.getCertificate(KEY_ALIAS)
        // Extract raw 32 bytes from the DER-encoded SubjectPublicKeyInfo
        val derBytes = cert.publicKey.encoded
        val rawBytes = derBytes.takeLast(32).toByteArray()
        return Base64.getEncoder().encodeToString(rawBytes)
    }

    fun buildAuthHeader(): String {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val message = "Firn-Ed25519:$ts".toByteArray(Charsets.UTF_8)
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val privateKey = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val sig = Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(message)
            Base64.getEncoder().encodeToString(sign())
        }
        return "Firn-Ed25519 ${getPublicKeyBase64()} $sig $ts"
    }
}
```

Making a request (skip TLS verification for self-signed cert):
```kotlin
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.net.ssl.*

// Trust-all TrustManager for self-signed cert (use cert pinning in production)
fun buildUnsafeOkHttpClient(): OkHttpClient {
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
    })
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    return OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
}

// Send a chat request
val client = buildUnsafeOkHttpClient()
val body = """{"model":"local","messages":[{"role":"user","content":"Hello!"}],"stream":false}"""
val request = Request.Builder()
    .url("https://192.168.1.42:8443/v1/chat/completions")   // URL shown on SRV
    .header("Authorization", FirnClient.buildAuthHeader())
    .header("Content-Type", "application/json")
    .post(body.toRequestBody("application/json".toMediaType()))
    .build()

client.newCall(request).execute().use { response ->
    println(response.body?.string())
}
```

### iOS / Swift

```swift
import Foundation
import CryptoKit

// CryptoKit's Curve25519.Signing is Ed25519. Persist the key (e.g. in the Keychain) —
// a new key needs pairing again.
let privateKey = Curve25519.Signing.PrivateKey()
let publicKeyBase64 = privateKey.publicKey.rawRepresentation.base64EncodedString()

func authHeader() -> String {
    let ts = String(Int(Date().timeIntervalSince1970))
    let message = "Firn-Ed25519:\(ts)".data(using: .utf8)!
    let signature = try! privateKey.signature(for: message).base64EncodedString()
    return "Firn-Ed25519 \(publicKeyBase64) \(signature) \(ts)"
}

var request = URLRequest(url: URL(string: "https://192.168.1.42:8443/v1/chat/completions")!)
request.httpMethod = "POST"
request.setValue(authHeader(), forHTTPHeaderField: "Authorization")
request.setValue("application/json", forHTTPHeaderField: "Content-Type")
request.httpBody = try! JSONSerialization.data(withJSONObject: [
    "model": "local",
    "messages": [["role": "user", "content": "Hello!"]]
])
// Note: configure URLSession to accept self-signed certs in development
```

---

## TLS / Certificate handling

firn uses a **self-signed certificate**, created when the server first starts and regenerated when the phone's WiFi IP changes (the cert carries that IP as a subject alternative name). A new cert has a new fingerprint — re-pin it (re-run `pair.py`).

Clients need to either:

### Option A — Skip verification (development only)
Use `-k` with curl, `verify=False` in Python requests, or a trust-all TrustManager in Android. Only acceptable for local testing.

### Option B — Pin the certificate fingerprint (recommended)

The pairing response includes the SHA-256 fingerprint of the server's DER certificate (uppercase hex, colon-separated):
```json
{"status": "paired", "serverCertFingerprint": "AA:BB:CC:DD:..."}
```

Fetch the certificate, compare its SHA-256 to this value, then trust only that certificate. [`examples/python/pair.py`](../examples/python/pair.py) does exactly this and [`chat.py`](../examples/python/chat.py) reuses the pinned cert.

---

## API Reference

### `GET /health` — no auth required

```bash
curl -k https://192.168.1.42:8443/health
```
```json
{"status": "ok", "model": "Qwen2.5-3B-Instruct-Q4_K_M", "loaded": true}
```

`model` is the local GGUF file name; it is omitted when no local model is loaded.

---

### `POST /v1/pair` — no auth required, PIN protected

```bash
curl -k -X POST https://192.168.1.42:8443/v1/pair \
  -H "Content-Type: application/json" \
  -d '{"clientPublicKey":"<b64>","pin":"482910","displayName":"My Laptop"}'
```
```json
{"status": "paired", "serverCertFingerprint": "AA:BB:CC:..."}
```

| Field | Required | Description |
|---|---|---|
| `clientPublicKey` | Yes | Base64 of raw 32-byte Ed25519 public key |
| `pin` | Yes | 6-digit PIN shown in the app |
| `displayName` | No | Name shown in the paired devices list (max 64 chars, default `Unknown Device`) |

A wrong or expired PIN returns `401 auth_error`; an invalid key returns `400 invalid_request_error`.

---

### `GET /v1/models` — auth required

```bash
curl -k https://192.168.1.42:8443/v1/models \
  -H "Authorization: Firn-Ed25519 <pubkey> <sig> <timestamp>"
```
```json
{"object": "list", "data": [{"id": "Qwen2.5-3B-Instruct-Q4_K_M", "object": "model", "created": 1718000000, "owned_by": "local"}]}
```

Lists the loaded local GGUF model; `data` is empty when none is loaded (including Ollama-only mode).

---

### `POST /v1/chat/completions` — auth required

```bash
curl -k -X POST https://192.168.1.42:8443/v1/chat/completions \
  -H "Authorization: Firn-Ed25519 <pubkey> <sig> <timestamp>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "What is 2+2?"}
    ],
    "stream": false,
    "temperature": 0.7,
    "max_tokens": 512,
    "top_p": 0.9
  }'
```

**Parameters:**

| Field | Type | Default | Description |
|---|---|---|---|
| `model` | string | required | Any string — ignored, the app's active model answers. With Ollama active, the model selected in the app is used |
| `messages` | array | required | `{role, content}` objects; `content` must be a string. Roles other than `system`/`assistant`/`user` are treated as `user`; empty messages are dropped. Without a system message, `You are a helpful assistant.` is added |
| `stream` | boolean | `false` | `true` for SSE token streaming |
| `temperature` | float | `0.7` | Sampling temperature |
| `max_tokens` | int | `512` | Maximum tokens to generate |
| `top_p` | float | `0.9` | Nucleus sampling threshold |
| `frequency_penalty` | float | `0.0` | Accepted for OpenAI compatibility, ignored |
| `presence_penalty` | float | `0.0` | Accepted for OpenAI compatibility, ignored |
| `stop` | array of strings | `null` | Accepted for OpenAI compatibility, ignored |
| `include_thinking` | boolean | `false` | firn extension — include reasoning from thinking models. Non-streaming: prefixed as `<think>…</think>`; streaming: emitted inline without tags |

Unknown fields (e.g. `tools`) are ignored.

**Response (non-streaming):**
```json
{
  "id": "chatcmpl-<uuid>",
  "object": "chat.completion",
  "created": 1718000000,
  "model": "Qwen2.5-3B-Instruct-Q4_K_M",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "4"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 24, "completion_tokens": 1, "total_tokens": 25}
}
```

`usage` counts are whitespace-separated words, not tokenizer tokens.

**Response (streaming, `stream: true`):**

Server-Sent Events stream:
```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant"}}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"4"}}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

If generation fails or times out mid-stream, a `data: {"error":"server_busy"}` event precedes the final stop chunk.

---

## Using with OpenAI SDKs

Since the API is OpenAI-compatible, you can use the official SDK directly.
The SDK sends `Authorization: Bearer <api_key>`, so replace that header on every request:

### Python openai SDK

```python
import base64, os, time, openai
from cryptography.hazmat.primitives.serialization import load_pem_private_key, Encoding, PublicFormat

with open(os.path.expanduser("~/.firn/client_key.pem"), "rb") as f:
    private_key = load_pem_private_key(f.read(), password=None)
PUBKEY_B64 = base64.b64encode(
    private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
).decode()

def sign_request(request):
    ts = str(int(time.time()))
    sig = base64.b64encode(private_key.sign(f"Firn-Ed25519:{ts}".encode())).decode()
    request.headers["Authorization"] = f"Firn-Ed25519 {PUBKEY_B64} {sig} {ts}"

client = openai.OpenAI(
    base_url="https://192.168.1.42:8443/v1",
    api_key="not-used",                  # required by SDK, replaced by sign_request
    http_client=openai.DefaultHttpxClient(
        verify=False,                    # or a pinned cert, see TLS
        event_hooks={"request": [sign_request]},
    ),
)

response = client.chat.completions.create(
    model="local",
    messages=[{"role": "user", "content": "Hello!"}]
)
print(response.choices[0].message.content)
```

---

## Error Reference

Auth errors are `{"error": "<message>", "type": "auth_error"}`; all others are `{"error": {"message": "...", "type": "..."}}`.

| HTTP Status | `type` field | Meaning |
|---|---|---|
| `401` | `auth_error` | Missing, malformed, or invalid Authorization header |
| `401` | `auth_error` | Public key not registered — pair the device first |
| `401` | `auth_error` | Timestamp outside ±30s window — sync your clock |
| `401` | `auth_error` | Pairing: invalid or expired PIN |
| `400` | `invalid_request_error` | Malformed JSON or invalid public key |
| `413` | `invalid_request_error` | Request body exceeds 1 MB |
| `503` | `model_not_loaded` | No model loaded in the app |
| `503` | `server_busy` | Engine busy or generation timed out (2 min limit, including the wait for another request) |

---

## Troubleshooting

**`401 Missing Authorization header`**
→ You're not sending the `Authorization` header. Check your client code.

**`401 Timestamp outside allowed window`**
→ Your computer's clock differs from the phone's by more than 30 seconds. Turn on automatic time sync on both devices.

**`401 Public key not registered`**
→ You haven't paired this device yet, or the paired key was removed.
On SRV tap **Pair a new device** and pair again.

**Connection refused / timeout**
→ Phone and laptop must be on the **same WiFi network**.
Check that the address on SRV (or in the notification) matches what you're connecting to. The server does not listen on hotspot or VPN interfaces.

**Server stopped by itself**
→ Android 15+ limits `dataSync` foreground services to 6 hours per 24 hours; firn stops the server when the limit runs out. Start it again on SRV.

**SSL error / certificate verify failed**
→ Expected with default verification — firn uses a self-signed cert. Use `-k` (curl) or `verify=False` (Python) for development, or pin the cert as described above. If pinning suddenly fails, the phone's IP changed and the cert was regenerated; re-run `pair.py`.

**`503 model_not_loaded`**
→ Open firn and load a model on MDL, or enable Ollama in the chat Modes menu.
