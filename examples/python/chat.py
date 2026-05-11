#!/usr/bin/env python3
"""
chat.py — Send a message to your LamaPhone server.

Usage:
    python3 chat.py "What is the capital of Switzerland?"
    python3 chat.py "Tell me a joke" --stream

Run pair.py first if you haven't paired this machine yet.
"""

import argparse
import base64
import json
import os
import ssl
import time
import requests
from requests.adapters import HTTPAdapter
from urllib3.poolmanager import PoolManager
from cryptography.hazmat.primitives.serialization import (
    load_pem_private_key,
    Encoding,
    PublicFormat,
)

KEY_FILE = os.path.expanduser("~/.lamaphone/client_key.pem")
CFG_FILE = os.path.expanduser("~/.lamaphone/config.json")


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
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_REQUIRED
        self.poolmanager = PoolManager(
            num_pools=num_pools, maxsize=maxsize, block=block,
            ssl_context=ctx,
            assert_hostname=False,  # disable urllib3's own hostname check
            **kw
        )


def load_credentials():
    if not os.path.exists(KEY_FILE) or not os.path.exists(CFG_FILE):
        raise SystemExit(
            "No credentials found. Run pair.py first:\n  python3 pair.py"
        )
    with open(KEY_FILE, "rb") as f:
        private_key = load_pem_private_key(f.read(), password=None)
    with open(CFG_FILE) as f:
        config = json.load(f)
    return private_key, config


def auth_header(private_key) -> str:
    pubkey_b64 = base64.b64encode(
        private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    ).decode()
    ts = str(int(time.time()))
    message = f"LamaPhone-Ed25519:{ts}".encode()
    sig = base64.b64encode(private_key.sign(message)).decode()
    return f"LamaPhone-Ed25519 {pubkey_b64} {sig} {ts}"


def chat(prompt: str, stream: bool = False):
    private_key, config = load_credentials()
    headers = {
        "Authorization": auth_header(private_key),
        "Content-Type": "application/json",
    }
    payload = {
        "model": "local",
        "messages": [{"role": "user", "content": prompt}],
        "stream": stream,
    }

    session = requests.Session()
    cert_file = config.get("certFile")
    if cert_file and os.path.exists(cert_file):
        session.mount("https://", PinnedCertAdapter(cert_file))
    else:
        # Fallback if paired without cert pinning (older config)
        session.verify = False

    resp = session.post(
        f"{config['server']}/v1/chat/completions",
        headers=headers,
        json=payload,
        timeout=120,
        stream=stream,
    )
    if not resp.ok:
        raise SystemExit(f"Error {resp.status_code}: {resp.text}")

    if stream:
        for line in resp.iter_lines():
            if line and line.startswith(b"data: "):
                data = line[6:]
                if data == b"[DONE]":
                    break
                chunk = json.loads(data)
                delta = chunk["choices"][0]["delta"].get("content", "")
                print(delta, end="", flush=True)
        print()
    else:
        print(resp.json()["choices"][0]["message"]["content"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Send a message to LamaPhone")
    parser.add_argument("prompt", help="The message to send")
    parser.add_argument("--stream", action="store_true", help="Stream the response")
    args = parser.parse_args()
    chat(args.prompt, stream=args.stream)
