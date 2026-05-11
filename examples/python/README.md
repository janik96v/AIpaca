# LamaPhone – Python Examples

Connect to your LamaPhone server from any Python script using Ed25519 authentication over HTTPS.

## Requirements

```bash
pip install -r requirements.txt
```

## Quickstart

### 1. Start the server on your phone

Open LamaPhone → **Server tab** → load a model → tap **START_SERVER**.

Note the address shown (e.g. `https://192.168.1.42:8443`). Your Mac and phone must be on the same WiFi network.

### 2. Pair your machine (once)

On your phone tap **PAIR_NEW_DEVICE** and note the 6-digit PIN. Then run:

```bash
python3 pair.py
```

Enter the server URL, PIN, and a device name when prompted. Your key pair and the server config are saved to `~/.lamaphone/`.

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
Authorization: LamaPhone-Ed25519 <base64-pubkey> <base64-signature> <unix-timestamp>
```

The signed message is `LamaPhone-Ed25519:<timestamp>`. The timestamp must be within **±30 seconds** of the phone's clock. Your private key never leaves your machine — the phone stores only the public key.

## TLS note

LamaPhone uses a self-signed certificate. The scripts use `verify=False` for development convenience. For production use, pin the certificate fingerprint returned by the pairing response — see the full [API Client Guide](../../docs/api-client-guide.md) for details.

## Troubleshooting

| Error | Fix |
|---|---|
| `401 Public key not registered` | Run `pair.py` again |
| `401 Timestamp outside allowed window` | Sync your clock: `sudo ntpdate pool.ntp.org` |
| `503 model_not_loaded` | Load a GGUF model in the LamaPhone app first |
| Connection refused | Check that phone and Mac are on the same WiFi |
| SSL error | Expected — use `verify=False` or see cert pinning in the API guide |

For the full API reference, error codes, and examples in other languages (curl, Kotlin, Swift), see [`docs/api-client-guide.md`](../../docs/api-client-guide.md).
