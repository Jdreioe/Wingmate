#!/usr/bin/env python3
"""Pair an Android device with adb over Wi-Fi using a QR code.

Replicates the Android Studio "Pair Devices Using Wi-Fi (QR code)" flow:

1. Generates a random service name + SPAKE2 password.
2. Renders the QR code (`WIFI:T:ADB;S:<name>;P:<password>;;`) in the terminal.
3. Scans the QR with the phone: Settings -> Developer options ->
   Wireless debugging -> Pair device with QR code.
4. Watches `adb mdns services` for the phone's `_adb-tls-pairing._tcp`
   advertisement, then runs `adb pair` with the same password.

Requirements:
    pip install qrcode
    adb on PATH (or ANDROID_ADB env var / default SDK path).
"""

import argparse
import os
import re
import secrets
import shutil
import subprocess
import sys
import time

PAIR_SERVICE_RE = re.compile(
    r"^(\S+)\s+_adb-tls-pairing\._tcp\s+(\d+\.\d+\.\d+\.\d+):(\d+)",
    re.MULTILINE,
)


def find_adb() -> str:
    adb = os.environ.get("ANDROID_ADB") or shutil.which("adb")
    if adb:
        return adb
    default = os.path.expanduser(
        "~/Library/Android/sdk/platform-tools/adb"
        if sys.platform == "darwin"
        else "~/Android/Sdk/platform-tools/adb"
    )
    if os.path.exists(default):
        return default
    sys.exit("adb not found; set ANDROID_ADB or install platform-tools")


def mdns_services(adb: str) -> list[tuple[str, str, int]]:
    out = subprocess.run(
        [adb, "mdns", "services"], capture_output=True, text=True, timeout=10
    )
    return [
        (name, host, int(port))
        for name, host, port in PAIR_SERVICE_RE.findall(out.stdout)
    ]


def main() -> None:
    global PASSWORD
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=120, help="seconds to wait for scan")
    args = parser.parse_args()

    try:
        import qrcode  # noqa: PLC0415
    except ImportError:
        sys.exit("missing dependency: pip install qrcode")

    adb = find_adb()
    name = f"adb-{secrets.token_hex(4)}"
    PASSWORD = str(secrets.randbelow(900000) + 100000)

    qr_text = f"WIFI:T:ADB;S:{name};P:{PASSWORD};;"
    print(f"Service: {name}\nPassword: {PASSWORD}\n")
    qr = qrcode.QRCode(border=1)
    qr.add_data(qr_text)
    qr.print_ascii(invert=True)
    print("\nOn phone: Settings -> Developer options -> Wireless debugging")
    print("          -> Pair device with QR code\n")

    deadline = time.time() + args.timeout
    while time.time() < deadline:
        for name, host, port in mdns_services(adb):
            print(f"Found pairing service '{name}' at {host}:{port}, pairing…")
            result = subprocess.run(
                [adb, "pair", f"{host}:{port}", PASSWORD], text=True
            )
            if result.returncode == 0:
                subprocess.run([adb, "connect", f"{host}:{port}"], text=True)
                print("Paired.")
                return
            print("Pairing failed; waiting for a fresh scan…")
            break
        time.sleep(2)
    print("Timed out waiting for device scan.")


if __name__ == "__main__":
    main()
