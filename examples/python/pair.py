#!/usr/bin/env python3
"""
pair.py — Run this once to register your machine with LamaPhone.

Usage:
    pip install -r requirements.txt
    python3 pair.py

On your phone: Server tab → PAIR_NEW_DEVICE → enter the PIN shown here.
After pairing, your key and the server config are saved to ~/.lamaphone/.
"""

import base64
import hashlib
import json
import os
import socket
import ssl
import requests
from requests.adapters import HTTPAdapter
from urllib3.poolmanager import PoolManager
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PublicFormat,
    PrivateFormat,
    NoEncryption,
    load_pem_private_key,
)

KEY_FILE  = os.path.expanduser("~/.lamaphone/client_key.pem")
CFG_FILE  = os.path.expanduser("~/.lamaphone/config.json")
CERT_FILE = os.path.expanduser("~/.lamaphone/server.pem")
os.makedirs(os.path.dirname(KEY_FILE), exist_ok=True)


class PinnedCertAdapter(HTTPAdapter):
    """Verify TLS cert against pinned file but skip hostname check.
    LamaPhone's self-signed cert has CN=LamaPhone (no IP SAN), so hostname
    verification always fails. Security is maintained via fingerprint pinning.

    Note: urllib3 1.x has TWO hostname checks — one in ssl.SSLContext and one
    internal to urllib3. Both must be disabled via check_hostname=False and
    assert_hostname=False."""
    def __init__(self, certfile, **kwargs):
        self.certfile = certfile
        super().__init__(**kwargs)

    def init_poolmanager(self, num_pools, maxsize, block=False, **kw):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.load_verify_locations(self.certfile)
        ctx.check_hostname = False   # disable ssl module hostname check
        ctx.verify_mode = ssl.CERT_REQUIRED  # cert signature still verified
        self.poolmanager = PoolManager(
            num_pools=num_pools, maxsize=maxsize, block=block,
            ssl_context=ctx,
            assert_hostname=False,  # disable urllib3's own hostname check
            **kw
        )


def fetch_and_save_cert(host: str, port: int) -> str:
    """Download the server's TLS cert (TOFU) and return its SHA-256 fingerprint."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port), timeout=5) as sock:
        with ctx.wrap_socket(sock, server_hostname=host) as tls:
            der = tls.getpeercert(binary_form=True)
    # Save as PEM so requests can use it for cert pinning
    pem = ssl.DER_cert_to_PEM_cert(der)
    with open(CERT_FILE, "w") as f:
        f.write(pem)
    fingerprint = hashlib.sha256(der).hexdigest()
    return ":".join(fingerprint[i:i+2].upper() for i in range(0, len(fingerprint), 2))

# Generate key pair (skipped if already exists)
if not os.path.exists(KEY_FILE):
    private_key = Ed25519PrivateKey.generate()
    with open(KEY_FILE, "wb") as f:
        f.write(private_key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
    print(f"Generated new key pair → {KEY_FILE}")
else:
    with open(KEY_FILE, "rb") as f:
        private_key = load_pem_private_key(f.read(), password=None)
    print(f"Loaded existing key from {KEY_FILE}")

pubkey_b64 = base64.b64encode(
    private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
).decode()

# Pairing
server = input("Server URL (e.g. https://192.168.1.42:8443): ").strip().rstrip("/")
pin    = input("PIN shown on phone: ").strip()
name   = input("Device name [My Mac]: ").strip() or "My Mac"

# Fetch and pin the server cert before pairing (TOFU — Trust On First Use)
from urllib.parse import urlparse
parsed = urlparse(server)
host, port = parsed.hostname, parsed.port or 8443
print("Fetching server certificate...")
local_fingerprint = fetch_and_save_cert(host, port)
print(f"Certificate fingerprint (local):  {local_fingerprint}")

print("Pairing...")
session = requests.Session()
session.mount("https://", PinnedCertAdapter(CERT_FILE))
resp = session.post(
    f"{server}/v1/pair",
    json={"clientPublicKey": pubkey_b64, "pin": pin, "displayName": name},
    timeout=10,
)
resp.raise_for_status()
data = resp.json()
server_fingerprint = data["serverCertFingerprint"]
print(f"Certificate fingerprint (server): {server_fingerprint}")

if local_fingerprint != server_fingerprint:
    raise SystemExit("⚠️  Certificate fingerprint mismatch — possible MITM attack! Aborting.")
print("Fingerprints match ✓")

# Save config
config = {"server": server, "certFile": CERT_FILE, "certFingerprint": server_fingerprint}
with open(CFG_FILE, "w") as f:
    json.dump(config, f, indent=2)
print(f"Config saved → {CFG_FILE}")
print("\nYou can now use chat.py to send requests (no more SSL warnings).")
