#!/usr/bin/env python3
# KV-Cache-Groesse pro Token und pro Kontextlaenge fuer llama.cpp
# Formel: bytes_per_token = 2 * n_layers * n_kv_heads * head_dim * bytes_per_elem
# (Faktor 2 = K und V zusammen)

def head_dim(hidden, n_heads, explicit=None):
    return explicit if explicit else hidden // n_heads

# bytes pro Element je KV-Cache-Typ (llama.cpp)
# f16 = 2.0 B/elem exakt.
# q8_0: Block 32 Werte -> 32*1 Byte + 1 fp16-Scale (2 B) = 34 B / 32 = 1.0625 B/elem
# q4_0: Block 32 Werte -> 32*0.5 Byte + 1 fp16-Scale (2 B) = 18 B / 32 = 0.5625 B/elem
BYTES = {"f16": 2.0, "q8_0": 34/32, "q4_0": 18/32}

models = {
    "Qwen2.5-3B-Instruct": dict(n_layers=36, n_kv_heads=2, hidden=2048, n_heads=16, hd=None),
    "Mistral-7B-Instruct-v0.3": dict(n_layers=32, n_kv_heads=8, hidden=4096, n_heads=32, hd=None),
    "Llama-3.2-3B-Instruct": dict(n_layers=28, n_kv_heads=8, hidden=3072, n_heads=24, hd=128),
    "Qwen3-4B-Instruct-2507": dict(n_layers=36, n_kv_heads=8, hidden=2560, n_heads=32, hd=128),
    "Phi-4-mini-instruct": dict(n_layers=32, n_kv_heads=8, hidden=3072, n_heads=24, hd=None, prot=0.75),
    "Gemma-2-2b-it": dict(n_layers=26, n_kv_heads=4, hidden=2304, n_heads=8, hd=256),
}

ctx_list = [4096, 8192, 16384, 32768]

for name, m in models.items():
    hd = head_dim(m["hidden"], m["n_heads"], m["hd"])
    # Phi-4-mini partial rotary faktor beeinflusst NICHT die KV-cache dim in llama.cpp (voller head_dim gespeichert)
    print(f"\n### {name}")
    print(f"  n_layers={m['n_layers']} n_kv_heads={m['n_kv_heads']} head_dim={hd}")
    for tname, bpe in BYTES.items():
        per_tok = 2 * m["n_layers"] * m["n_kv_heads"] * hd * bpe
        row = []
        for ctx in ctx_list:
            mb = per_tok * ctx / (1024*1024)
            row.append(f"{ctx//1024}k={mb:6.1f}MB")
        print(f"  {tname:5s}: {per_tok/1024:5.2f} KB/tok | " + " ".join(row))
