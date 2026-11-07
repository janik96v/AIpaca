# firn – Python Examples

Connect to your firn server from any Python script using Ed25519 authentication over HTTPS.

## Requirements

```bash
pip install -r requirements.txt
```

## Quickstart

### 1. Start the server on your phone

Open firn, load a model on **MDL**, then open **SRV** and tap **Start server**.

Note the address shown (e.g. `https://192.168.1.42:8443`). Your Mac and phone must be on the same WiFi network.

### 2. Pair your machine (once)

On **SRV** tap **Pair a new device** and note the 6-digit PIN. Then run:

```bash
python3 pair.py
```

Enter the server URL, PIN, and a device name when prompted. Your key pair, the pinned server certificate and the config are saved to `~/.firn/`.

### 3. Send a request

```bash
python3 chat.py "What is the capital of Switzerland?"
```

For streamed output:

```bash
python3 chat.py "Tell me a short story" --stream
```

## How authentication works

Every request includes a signed `Authorization` header:

```
Authorization: Firn-Ed25519 <base64-pubkey> <base64-signature> <unix-timestamp>
```

The signed message is `Firn-Ed25519:<timestamp>`. The timestamp must be within **±30 seconds** of the phone's clock. Your private key never leaves your machine — the phone stores only the public key.

## TLS note

firn uses a self-signed certificate. `pair.py` downloads it, checks it against the fingerprint returned by the pairing response and saves it as `~/.firn/server.pem`; `chat.py` then trusts only that certificate. If the phone's IP changes, firn regenerates the certificate — run `pair.py` again.

## Troubleshooting

| Error | Fix |
|---|---|
| `401 Public key not registered` | Run `pair.py` again |
| `401 Timestamp outside allowed window` | Sync your clock: `sudo ntpdate pool.ntp.org` |
| `503 model_not_loaded` | Load a model in the firn app first (MDL) |
| Connection refused | Check that phone and Mac are on the same WiFi |
| SSL error / certificate verify failed | The server certificate changed — run `pair.py` again |

For the full API reference, error codes, and examples in other languages (curl, Kotlin, Swift), see [`docs/api-client-guide.md`](../../docs/api-client-guide.md).
