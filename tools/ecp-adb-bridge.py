#!/usr/bin/env python3
"""Runs an Android ECP engine as if it were a desktop one, so a desktop test frontend such as
ecp-probe (emulator-control-protocol/conformance) can drive it over adb.

    python ecp-probe.py --transport tcp --content <path on the device> -- \
        python ecp-adb-bridge.py --serial <device> [--package com.izzy2lost.psx2.ecp] [--menu-after 20]

ecp-probe starts this script with "--ecp tcp:127.0.0.1:<port>" and the token in ECP_TOKEN.
The script maps the device's loopback port to the PC with "adb reverse", starts the engine's
PLAY activity with the ECP extras, and lives until the engine's process ends. With
"--ecp-describe" it prints the descriptor the APK carries (res/raw/ecp.json).

The game is passed in io.github.ecp.extra.PATH: give ecp-probe a path the app can read, such as
/sdcard/Android/data/<package>/files/<game>. A content:// URI with a read grant cannot be made
from adb. MENU_INTENT (a PendingIntent) cannot be sent from adb either; --menu-after presses
Back on the device after that many seconds, which PSX2 ECP turns into a menu_request.
"""
import argparse
import json
import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB", "adb")


def adb(serial, *args, check=True, capture=True):
    cmd = [ADB, "-s", serial] + list(args)
    p = subprocess.run(cmd, capture_output=capture, text=True)
    if check and p.returncode != 0:
        raise RuntimeError("%s: %s" % (" ".join(cmd), (p.stderr or "").strip()))
    return (p.stdout or "").strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--package", default="com.izzy2lost.psx2.ecp")
    ap.add_argument("--activity", default="com.izzy2lost.psx2.EcpPlayActivity")
    ap.add_argument("--descriptor", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                                         "app", "src", "main", "res", "raw", "ecp.json"))
    ap.add_argument("--menu-after", type=float, default=0)
    ap.add_argument("--ecp")
    ap.add_argument("--ecp-token-env", default="ECP_TOKEN")
    ap.add_argument("--ecp-describe", action="store_true")
    ap.add_argument("--ecp-oneshot")
    args, _ = ap.parse_known_args()

    if args.ecp_oneshot:
        print(json.dumps({"ok": False, "error": {"code": "unsupported"}}))
        return 1
    if args.ecp_describe:
        with open(args.descriptor, encoding="utf-8") as f:
            desc = json.load(f)
        desc["id"] = args.package
        version = adb(args.serial, "shell", "dumpsys", "package", args.package, check=False)
        for line in version.splitlines():
            line = line.strip()
            if line.startswith("versionName="):
                desc["version"] = line.split("=", 1)[1]
                break
        print(json.dumps(desc))
        return 0
    if not args.ecp or not args.ecp.startswith("tcp:"):
        print("only --ecp tcp:<host>:<port> works on Android", file=sys.stderr)
        return 2

    port = int(args.ecp.rsplit(":", 1)[1])
    token = os.environ.get(args.ecp_token_env, "")
    adb(args.serial, "reverse", "tcp:%d" % port, "tcp:%d" % port)
    try:
        # The probe sends the game in launch; the activity still needs a data URI or PATH for
        # "intent" content, so the probe's --content path goes in both.
        content = os.environ.get("ECP_BRIDGE_PATH", "")
        cmd = ["shell", "am", "start", "-W", "-n", "%s/%s" % (args.package, args.activity),
               "-a", "io.github.ecp.action.PLAY",
               "--ei", "io.github.ecp.extra.PORT", str(port),
               "--es", "io.github.ecp.extra.TOKEN", token,
               "--es", "io.github.ecp.extra.SESSION", "adb-bridge-%d" % port]
        if content:
            cmd += ["--es", "io.github.ecp.extra.PATH", content]
        print(adb(args.serial, *cmd), file=sys.stderr)

        started = time.monotonic()
        pid = ""
        for _ in range(100):
            pid = adb(args.serial, "shell", "pidof", args.package, check=False)
            if pid:
                break
            time.sleep(0.1)
        print("engine pid %s" % pid, file=sys.stderr)
        pressed = False
        while pid:
            if args.menu_after and not pressed and time.monotonic() - started >= args.menu_after:
                adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK", check=False)
                pressed = True
            time.sleep(0.25)
            pid = adb(args.serial, "shell", "pidof", args.package, check=False)
        return 0
    finally:
        adb(args.serial, "reverse", "--remove", "tcp:%d" % port, check=False)


if __name__ == "__main__":
    sys.exit(main())
