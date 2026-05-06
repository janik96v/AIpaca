# LamaPhone API Client Guide

How to connect to and use the LamaPhone AI server from a laptop, another app, or any HTTP client.

---

## Overview

LamaPhone runs an **HTTPS server on port 8443** with an OpenAI-compatible API.
Every client must **pair once** before it can make API calls.

```
Client                          LamaPhone (your phone)
──────                          ──────────────────────
Pair once   ──── /v1/pair ────► Register your public key
API calls   ── signed header ►  Verify signature → respond
```

Authentication uses **Ed25519 asymmetric keys** — the same principle as SSH.
Your private key never leaves your device. The phone stores only your public key.

---

## Step 1 — Start the server on your phone

1. Open LamaPhone → **Server tab**
2. Load a GGUF model if you haven't already
3. Tap **START\_SERVER**

The notification and Server tab show:
```
https://192.168.1.42:8443
```
Note this address — you'll need it.

---

## Step 2 — Generate a pairing PIN on your phone

1. On the Server tab tap **PAIR\_NEW\_DEVICE**
2. A QR code appears with a 6-digit PIN below it (valid for 5 minutes)

You can either:
- **Scan the QR code** with a client that supports it (the QR encodes the full pairing payload)
- **Use the PIN manually** by following Step 3 below

---

## Step 3 — Pair your client

### Python (recommended)

Install dependencies once:
```bash
pip install cryptography requests
```

Run the pairing script once per device:
```python
#!/usr/bin/env python3
"""pair.py — run once to register this machine with LamaPhone"""

import base64, json, os, requests
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding, PublicFormat, PrivateFormat, NoEncryption
)

KEY_FILE = os.path.expanduser("~/.lamaphone/client_key.pem")
CFG_FILE = os.path.expanduser("~/.lamaphone/config.json")
os.makedirs(os.path.dirname(KEY_FILE), exist_ok=True)

# --- Generate key pair (skipped if already exists) ---
if not os.path.exists(KEY_FILE):
    private_key = Ed25519PrivateKey.generate()
    with open(KEY_FILE, "wb") as f:
        f.write(private_key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
    print("Generated new key pair →", KEY_FILE)
else:
    from cryptography.hazmat.primitives.serialization import load_pem_private_key
    with open(KEY_FILE, "rb") as f:
        private_key = load_pem_private_key(f.read(), password=None)
    print("Loaded existing key from", KEY_FILE)

public_key = private_key.public_key()
pubkey_b64 = base64.b64encode(
    public_key.public_bytes(Encoding.Raw, PublicFormat.Raw)
).decode()

# --- Pairing ---
server = input("Server URL (e.g. https://192.168.1.42:8443): ").strip().rstrip("/")
pin    = input("PIN shown on phone: ").strip()
name   = input("Device name (e.g. My Laptop) [Enter to skip]: ").strip() or "Client"

resp = requests.post(
    f"{server}/v1/pair",
    json={"clientPublicKey": pubkey_b64, "pin": pin, "displayName": name},
    verify=False   # self-signed cert — we'll pin the fingerprint next
)
resp.raise_for_status()
data = resp.json()
print("Paired! Server cert fingerprint:", data["serverCertFingerprint"])

# --- Save config ---
config = {"server": server, "certFingerprint": data["serverCertFingerprint"]}
with open(CFG_FILE, "w") as f:
    json.dump(config, f, indent=2)
print("Config saved →", CFG_FILE)
```

```bash
python3 pair.py
# Server URL: https://192.168.1.42:8443
# PIN: 482910
# Device name: My Laptop
```

### curl (manual pairing)

```bash
PHONE="https://192.168.1.42:8443"
PIN="482910"
PUBKEY="<your-base64-public-key>"   # see note below

curl -k -X POST "$PHONE/v1/pair" \
  -H "Content-Type: application/json" \
  -d "{\"clientPublicKey\":\"$PUBKEY\",\"pin\":\"$PIN\",\"displayName\":\"curl client\"}"
```

> **Note:** `-k` skips TLS verification for this one-time call. After pairing, the server returns its cert fingerprint — use that to pin the cert in future requests.

---

## Step 4 — Make API calls

Every request after pairing must include a signed `Authorization` header.

### Header format

```
Authorization: LamaPhone-Ed25519 <base64-pubkey> <base64-signature> <unix-timestamp>
```

The **signed message** is exactly:
```
LamaPhone-Ed25519:<unix-timestamp-seconds>
```

The timestamp must be within **±30 seconds** of the phone's clock.

---

### Python client (full example)

```python
#!/usr/bin/env python3
"""chat.py — send a message to LamaPhone"""

import base64, json, os, time, requests
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import load_pem_private_key

KEY_FILE = os.path.expanduser("~/.lamaphone/client_key.pem")
CFG_FILE = os.path.expanduser("~/.lamaphone/config.json")

# Load key and config
with open(KEY_FILE, "rb") as f:
    private_key = load_pem_private_key(f.read(), password=None)
with open(CFG_FILE) as f:
    config = json.load(f)

from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
PUBKEY_B64 = base64.b64encode(
    private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
).decode()

def auth_header() -> str:
    ts = str(int(time.time()))
    message = f"LamaPhone-Ed25519:{ts}".encode()
    sig = base64.b64encode(private_key.sign(message)).decode()
    return f"LamaPhone-Ed25519 {PUBKEY_B64} {sig} {ts}"

def chat(messages: list, stream: bool = False) -> str:
    resp = requests.post(
        f"{config['server']}/v1/chat/completions",
        headers={
            "Authorization": auth_header(),
            "Content-Type": "application/json",
        },
        json={"model": "local", "messages": messages, "stream": stream},
        verify=False,   # replace with cert pinning in production (see below)
        timeout=120,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]

# Example
reply = chat([{"role": "user", "content": "What is the capital of Switzerland?"}])
print(reply)
```

### Python — streaming response

```python
import sseclient   # pip install sseclient-py

def chat_stream(messages: list):
    resp = requests.post(
        f"{config['server']}/v1/chat/completions",
        headers={"Authorization": auth_header(), "Content-Type": "application/json"},
        json={"model": "local", "messages": messages, "stream": True},
        verify=False,
        stream=True,
        timeout=120,
    )
    client = sseclient.SSEClient(resp)
    for event in client.events():
        if event.data == "[DONE]":
            break
        chunk = json.loads(event.data)
        delta = chunk["choices"][0]["delta"].get("content", "")
        print(delta, end="", flush=True)
    print()

chat_stream([{"role": "user", "content": "Tell me a short story."}])
```

### curl (signed request)

Generate the header values manually:
```bash
# Requires openssl and python3
TIMESTAMP=$(date +%s)
MESSAGE="LamaPhone-Ed25519:$TIMESTAMP"
SIG=$(echo -n "$MESSAGE" | openssl pkeyutl -sign -inkey ~/.lamaphone/client_key.pem -out - | base64 | tr -d '\n')
PUBKEY=$(openssl pkey -in ~/.lamaphone/client_key.pem -pubout -outform DER 2>/dev/null | tail -c 32 | base64 | tr -d '\n')

curl -k -X POST "https://192.168.1.42:8443/v1/chat/completions" \
  -H "Authorization: LamaPhone-Ed25519 $PUBKEY $SIG $TIMESTAMP" \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"Hello!"}],"stream":false}'
```

> **Tip:** The Python client is much easier than constructing the header manually in bash. Use curl only for quick one-off tests.

---

## Connecting from another app on the same phone

If your client app runs on the **same Android device** as LamaPhone, use `localhost` or `127.0.0.1` as the host — no WiFi required.

```
https://127.0.0.1:8443
```

The pairing flow is identical: tap **PAIR\_NEW\_DEVICE** on the Server tab, use the PIN in your app.

### Android app (Kotlin / OkHttp)

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

object LamaPhoneClient {

    private const val KEY_ALIAS = "lamaphone_client"
    private const val SERVER = "https://127.0.0.1:8443"

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
        val message = "LamaPhone-Ed25519:$ts".toByteArray(Charsets.UTF_8)
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val privateKey = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val sig = Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(message)
            Base64.getEncoder().encodeToString(sign())
        }
        return "LamaPhone-Ed25519 ${getPublicKeyBase64()} $sig $ts"
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
    .url("https://127.0.0.1:8443/v1/chat/completions")
    .header("Authorization", LamaPhoneClient.buildAuthHeader())
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

// Key generation — stored in Keychain via SecureEnclave or P256
// Note: Apple's CryptoKit uses Curve25519 (same as Ed25519) for signing
let privateKey = Curve25519.Signing.PrivateKey()
let publicKeyBase64 = privateKey.publicKey.rawRepresentation.base64EncodedString()

func authHeader() -> String {
    let ts = String(Int(Date().timeIntervalSince1970))
    let message = "LamaPhone-Ed25519:\(ts)".data(using: .utf8)!
    let signature = try! privateKey.signature(for: message).base64EncodedString()
    return "LamaPhone-Ed25519 \(publicKeyBase64) \(signature) \(ts)"
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

LamaPhone uses a **self-signed certificate** generated on first launch. Clients need to either:

### Option A — Skip verification (development only)
Use `-k` with curl, `verify=False` in Python requests, or a trust-all TrustManager in Android. Only acceptable for local testing.

### Option B — Pin the certificate fingerprint (recommended)

The pairing response includes the server's certificate SHA-256 fingerprint:
```json
{"status": "paired", "serverCertFingerprint": "AA:BB:CC:DD:..."}
```

In Python, download and save the cert on first connection:
```python
import ssl, socket

def fetch_cert_der(host: str, port: int) -> bytes:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port)) as sock:
        with ctx.wrap_socket(sock) as ssock:
            return ssock.getpeercert(binary_form=True)

# Save on first connection
cert_der = fetch_cert_der("192.168.1.42", 8443)
with open(os.path.expanduser("~/.lamaphone/server.der"), "wb") as f:
    f.write(cert_der)

# Then in future requests, use the saved cert:
requests.post(url, ..., verify=os.path.expanduser("~/.lamaphone/server.der"))
```

---

## API Reference

### `GET /health` — no auth required

```bash
curl -k https://192.168.1.42:8443/health
```
```json
{"status": "ok", "model": "Qwen2.5-3B-Instruct-Q4_K_M", "loaded": true}
```

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
| `displayName` | No | Human-readable name shown in the paired clients list |

---

### `GET /v1/models` — auth required

```bash
curl -k https://192.168.1.42:8443/v1/models \
  -H "Authorization: LamaPhone-Ed25519 <pubkey> <sig> <timestamp>"
```
```json
{"object": "list", "data": [{"id": "Qwen2.5-3B-Instruct-Q4_K_M", "object": "model"}]}
```

---

### `POST /v1/chat/completions` — auth required

```bash
curl -k -X POST https://192.168.1.42:8443/v1/chat/completions \
  -H "Authorization: LamaPhone-Ed25519 <pubkey> <sig> <timestamp>" \
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
| `model` | string | — | Any string (ignored, one model loaded at a time) |
| `messages` | array | — | Array of `{role, content}` objects |
| `stream` | boolean | `false` | `true` for SSE token streaming |
| `temperature` | float | `0.7` | Sampling temperature (0.0–2.0) |
| `max_tokens` | int | `512` | Maximum tokens to generate |
| `top_p` | float | `0.9` | Nucleus sampling threshold |

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

**Response (streaming, `stream: true`):**

Server-Sent Events stream:
```
data: {"id":"chatcmpl-...","choices":[{"delta":{"role":"assistant"}}]}

data: {"id":"chatcmpl-...","choices":[{"delta":{"content":"4"}}]}

data: {"id":"chatcmpl-...","choices":[{"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

---

## Using with OpenAI SDKs

Since the API is OpenAI-compatible, you can use the official SDK directly.
You only need a small auth adapter to inject the signed header.

### Python openai SDK

```python
import time, base64, openai
from cryptography.hazmat.primitives.serialization import load_pem_private_key, Encoding, PublicFormat

with open(os.path.expanduser("~/.lamaphone/client_key.pem"), "rb") as f:
    private_key = load_pem_private_key(f.read(), password=None)
PUBKEY_B64 = base64.b64encode(
    private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
).decode()

def make_auth_header():
    ts = str(int(time.time()))
    sig = base64.b64encode(private_key.sign(f"LamaPhone-Ed25519:{ts}".encode())).decode()
    return f"LamaPhone-Ed25519 {PUBKEY_B64} {sig} {ts}"

client = openai.OpenAI(
    base_url="https://192.168.1.42:8443/v1",
    api_key="not-used",                  # required by SDK, ignored by server
    http_client=openai.DefaultHttpxClient(
        verify=False,                    # or pass cert path
        headers={"Authorization": make_auth_header()}
    )
)

response = client.chat.completions.create(
    model="local",
    messages=[{"role": "user", "content": "Hello!"}]
)
print(response.choices[0].message.content)
```

> **Note:** The `Authorization` header is generated once at client creation. For long-running scripts, regenerate it periodically (the ±30s timestamp window will expire otherwise). Create a new `openai.OpenAI` instance or intercept requests to refresh the header.

---

## Error Reference

| HTTP Status | `type` field | Meaning |
|---|---|---|
| `401` | `auth_error` | Missing, malformed, or invalid Authorization header |
| `401` | `auth_error` | Public key not registered — pair the device first |
| `401` | `auth_error` | Timestamp outside ±30s window — sync your clock |
| `400` | `invalid_request_error` | Malformed JSON or invalid public key |
| `413` | `invalid_request_error` | Request body exceeds 1 MB |
| `503` | `model_not_loaded` | No model loaded in the app |
| `503` | `server_busy` | Generation timed out (2 min limit) |

---

## Troubleshooting

**`401 Missing Authorization header`**
→ You're not sending the `Authorization` header. Check your client code.

**`401 Timestamp outside allowed window`**
→ Your computer's clock differs from the phone's by more than 30 seconds.
Run `sudo ntpdate pool.ntp.org` (Linux/macOS) to sync.

**`401 Public key not registered`**
→ You haven't paired this device yet, or the paired key was removed.
Go to Server tab → PAIR\_NEW\_DEVICE and pair again.

**Connection refused / timeout**
→ Phone and laptop must be on the **same WiFi network**.
Check the IP shown in the Server tab notification matches what you're connecting to.

**SSL error / certificate verify failed**
→ Expected — LamaPhone uses a self-signed cert. Use `-k` (curl) or `verify=False` (Python) for development, or pin the cert as described above.

**`503 model_not_loaded`**
→ Open LamaPhone and load a GGUF model from the Chat or Server tab first.
