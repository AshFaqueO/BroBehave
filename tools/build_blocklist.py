#!/usr/bin/env python3
"""Builds the adult-domain blocklist that the :detection module bundles and updates.

    python tools/build_blocklist.py                 # refresh the bundled asset
    python tools/build_blocklist.py --out dist/     # also write a signed update package

Source: StevenBlack/hosts "porn-only" list (MIT licence). The output is NOT a list of
names: it is a sorted array of 64-bit FNV-1a hashes ("BBL1" + count + hashes), the format
read by DomainMatcher.fromBytes. One canary name is added so tests can prove this script
and the Kotlin matcher hash names identically.

With --out, the script writes blocklist.bin and manifest.json (version, size, sha256,
signature). The signature is ECDSA P-256 / SHA-256 over the file, made with the private key
given by --key (PEM, kept OUTSIDE the repo). The app only accepts updates whose signature
verifies against BLOCKLIST_PUBLIC_KEY.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import struct
import subprocess
import sys
import time
import urllib.request

SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
CANARY = "blocklist-canary.brobehave.invalid"
ASSET = pathlib.Path(__file__).resolve().parent.parent / "detection/src/main/assets/blocklist.bin"
MASK = (1 << 64) - 1


def fnv1a64(name: str) -> int:
    """FNV-1a 64-bit, returned as a signed value like a Kotlin Long."""
    value = 0xCBF29CE484222325
    for byte in name.encode("utf-8"):
        value = ((value ^ byte) * 0x100000001B3) & MASK
    return value - (1 << 64) if value >= (1 << 63) else value


def read_domains(text: str) -> set:
    domains = set()
    for line in text.splitlines():
        parts = line.split("#", 1)[0].split()
        if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1"):
            name = parts[1].strip().rstrip(".").lower()
            if "." in name and name not in ("0.0.0.0", "localhost.localdomain"):
                domains.add(name)
    return domains


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", help="directory for a signed update package (blocklist.bin + manifest.json)")
    parser.add_argument("--key", help="ECDSA P-256 private key (PEM) used to sign the update")
    args = parser.parse_args()

    with urllib.request.urlopen(SOURCE, timeout=60) as response:
        domains = read_domains(response.read().decode("utf-8", "replace"))
    if len(domains) < 10_000:
        print(f"refusing to build: only {len(domains)} domains downloaded", file=sys.stderr)
        return 1
    domains.add(CANARY)

    hashes = sorted({fnv1a64(d) for d in domains})
    data = struct.pack(">4sI", b"BBL1", len(hashes)) + b"".join(struct.pack(">q", h) for h in hashes)
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    print(f"{len(hashes)} domains -> {ASSET.relative_to(ASSET.parents[4])} ({len(data)} bytes, sha256 {digest})")

    if args.out:
        if not args.key:
            print("--out needs --key (the signing key)", file=sys.stderr)
            return 1
        out = pathlib.Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        (out / "blocklist.bin").write_bytes(data)
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", args.key, str(out / "blocklist.bin")],
            check=True, capture_output=True,
        ).stdout
        manifest = {
            "version": int(time.time()),
            "size": len(data),
            "sha256": digest,
            "signature": base64.b64encode(signature).decode("ascii"),
        }
        (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(f"signed update package written to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
