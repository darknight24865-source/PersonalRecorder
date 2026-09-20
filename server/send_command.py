#!/usr/bin/env python3
"""Send a command to the connected PersonalRecorder app via the relay.

The relay forwards the command to the phone. If the phone is offline the
relay queues it (TTL 300 s) and delivers it on the next reconnect, so you
can fire commands even while the phone is between networks.

Usage examples:
    python3 send_command.py capture
    python3 send_command.py mic_start
    python3 send_command.py location_stop
    python3 send_command.py record_start
    python3 send_command.py deviceaudio_start
    python3 send_command.py call_video_start
    python3 send_command.py call_audio_start
    python3 send_command.py --url wss://host:8765 --insecure capture
    python3 send_command.py --timeout 10 record_start
"""
import argparse
import asyncio
import json
import ssl

import websockets

ALLOWED = {
    "capture",
    "mic_start", "mic_stop",
    "location_start", "location_stop",
    "record_start", "record_stop",
    "deviceaudio_start", "deviceaudio_stop",
    "call_video_start", "call_video_stop",
    "call_audio_start", "call_audio_stop",
}


async def main() -> None:
    parser = argparse.ArgumentParser(description="Send a command to the PersonalRecorder app")
    parser.add_argument("command", choices=sorted(ALLOWED))
    parser.add_argument("--url", default="ws://127.0.0.1:8765")
    parser.add_argument("--insecure", action="store_true",
                        help="skip TLS verification for wss with self-signed certs")
    parser.add_argument("--timeout", type=float, default=5.0,
                        help="seconds to wait for the relay ack (default 5)")
    args = parser.parse_args()

    ssl_ctx = None
    if args.url.startswith("wss://") and args.insecure:
        ssl_ctx = ssl.create_default_context()
        ssl_ctx.check_hostname = False
        ssl_ctx.verify_mode = ssl.CERT_NONE

    message = json.dumps({"type": args.command})
    try:
        async with websockets.connect(args.url, ssl=ssl_ctx) as ws:
            await ws.send(message)
            ack = await asyncio.wait_for(ws.recv(), timeout=args.timeout)
            reply = json.loads(ack)
            if reply.get("type") == "cmd_accepted":
                if reply.get("queued"):
                    print(f"[*] '{args.command}' queued -- device offline, "
                          "will be delivered on reconnect")
                else:
                    print(f"[*] '{args.command}' delivered to device")
            else:
                print(f"[*] relay replied: {ack}")
    except asyncio.TimeoutError:
        print(f"[*] sent '{args.command}' (no ack within {args.timeout}s)")


if __name__ == "__main__":
    asyncio.run(main())