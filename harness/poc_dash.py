#!/usr/bin/env python3
"""End-to-end smoke test for the PersonalRecorder dashboard (aiohttp).

Covers: token auth (redirect, bad/good login), multi-device registry
(device_online announce -> /api/devices), per-device command targeting
(`to: <device-id>`), per-device offline queues + flush on reconnect,
dev-tagged media (/upload?dev= + /api/files?dev= filter), media index +
bytes serving, multipart upload with out-of-order parts, UI WS live
events + commands.

Environment overrides (used by CI):
  PR_BASE  http base of the dashboard   (default http://127.0.0.1:8899)
  PR_WS    ws base of the dashboard     (default ws://127.0.0.1:8899)
  PR_TOKEN dashboard token              (default dashsecret)
"""
import asyncio
import base64
import json
import os
import sys

import aiohttp
import websockets

BASE = os.environ.get("PR_BASE", "http://127.0.0.1:8899")
WS = os.environ.get("PR_WS", "ws://127.0.0.1:8899")
TOKEN = os.environ.get("PR_TOKEN", "dashsecret")

DEV_A = "dev-a"
DEV_A_NAME = "Phone A"
DEV_B = "dev-b"
DEV_B_NAME = "Phone B"

RESULTS = []
SESS_COOKIE = ""  # set after login; applied manually because aiohttp's jar drops it silently


def check(name: str, ok: bool, detail: str = "", skip: bool = False) -> None:
    if skip:
        RESULTS.append((name, None))
        print(f"[SKIP] {name} :: {detail or 'pre-existing media state, nothing new to assert'}", flush=True)
        return
    RESULTS.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name} :: {detail}", flush=True)


async def http(session, method, path, cookie=True, **kw):
    headers = dict(kw.pop("headers", {}))
    if cookie and SESS_COOKIE:
        headers["Cookie"] = SESS_COOKIE
    async with session.request(method, BASE + path, headers=headers, **kw) as r:
        body = await r.read()
        return r.status, dict(r.headers), body


async def main() -> int:
    async with aiohttp.ClientSession() as s:

        # ---- 1. auth redirect -------------------------------------------------
        status, hdrs, body = await http(s, "GET", "/", allow_redirects=False)
        check("GET / unauthenticated -> 302 login", status == 302 and "/login" in hdrs.get("Location", ""),
              f"status={status} loc={hdrs.get('Location')}")

        status, hdrs, body = await http(s, "GET", "/api/files", allow_redirects=False)
        check("GET /api/files unauthenticated -> 302", status == 302, f"status={status}")

        # ---- 2. login ---------------------------------------------------------
        status, hdrs, body = await http(s, "POST", "/login",
                                        data={"token": "wrong"}, allow_redirects=False)
        check("POST /login wrong token -> login?bad=1", status == 302 and "bad=1" in hdrs.get("Location", ""),
              f"status={status} loc={hdrs.get('Location')}")

        status, hdrs, body = await http(s, "POST", "/login",
                                        data={"token": TOKEN}, allow_redirects=False)
        cookie = hdrs.get("Set-Cookie", "")
        check("POST /login correct token -> cookie", status == 302 and "session=" in cookie,
              f"status={status} cookie={cookie[:60]}")
        sess_val = cookie.split("session=")[1].split(";")[0]
        global SESS_COOKIE
        SESS_COOKIE = "session=" + sess_val

        status, hdrs, body = await http(s, "GET", "/")
        check("GET / with cookie -> 200 HTML dashboard", status == 200 and b"Live log" in body and b"cmdbar" in body,
              f"status={status} len={len(body)}")

        # baseline media entries (so the run is idempotent on a dirty data dir)
        async def count_kind(kind: str) -> int:
            st0, _, b0 = await http(s, "GET", f"/api/files?kind={kind}")
            return len(json.loads(b0)) if st0 == 200 else -1

        base_img, base_video, base_audio = (await count_kind("img"),
                                            await count_kind("video"),
                                            await count_kind("audio"))

        status, hdrs, body = await http(s, "GET", "/api/files?kind=img")
        check("GET /api/files (empty) -> []", status == 200 and body.strip() == b"[]",
              f"status={status} body={body[:80]}", skip=base_img > 0)

        # ---- 3. device A: announce + screenshot + location + ack --------------
        tiny_jpg = base64.b64decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
            "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=="
        )

        async with websockets.connect(f"{WS}/ws?token={TOKEN}") as dev_a:
            # the real app announces itself on open (RealtimeClient.onOpen) with
            # {id, name, url} — that registers the phone in the device registry
            await dev_a.send(json.dumps({"type": "device_online", "ts": 1700000000000,
                                         "payload": {"id": DEV_A, "name": DEV_A_NAME, "url": "test"}}))
            await dev_a.send(json.dumps({"type": "screenshot", "ts": 1700000000000,
                                         "payload": {"data": base64.b64encode(tiny_jpg).decode(),
                                                     "width": 1, "height": 1, "path": "demo"}}))
            await dev_a.send(json.dumps({"type": "location", "ts": 1700000001000,
                                         "payload": {"lat": 12.9716, "lon": 77.5946, "accuracy": 12}}))
            await dev_a.send(json.dumps({"type": "ack", "payload": {"cmd": "capture"}, "ts": 1700000002000}))

            # UI command relay while device A is online
            async with websockets.connect(f"{WS}/ws/ui",
                                          additional_headers={"Cookie": SESS_COOKIE}) as ui:
                hello = json.loads(await ui.recv())
                check("UI WS greets with hello", hello.get("type") == "hello", f"{hello}")
                st = json.loads(await ui.recv())
                check("UI WS status device online", st.get("type") == "status" and st.get("device_online") is True,
                      f"{st}")

                # explicit `to` — with several known devices a command without
                # `to` goes to the anonymous bucket by design
                await ui.send(json.dumps({"type": "capture", "to": DEV_A}))
                forwarded = json.loads(await asyncio.wait_for(dev_a.recv(), timeout=3))
                check("UI command forwarded to device", forwarded.get("type") == "capture", f"{forwarded}")
                ack_ui = json.loads(await asyncio.wait_for(ui.recv(), timeout=3))
                check("UI gets cmd_accepted (live)", ack_ui.get("type") == "cmd_accepted" and ack_ui.get("queued") is False,
                      f"{ack_ui}")

            # registry reflects the announced device
            status, hdrs, body = await http(s, "GET", "/api/devices")
            devs = json.loads(body).get("devices", [])
            dev_a_rec = next((d for d in devs if d["id"] == DEV_A), None)
            check("GET /api/devices lists announced device A (online, named)",
                  status == 200 and dev_a_rec is not None
                  and dev_a_rec.get("name") == DEV_A_NAME and dev_a_rec.get("online") is True,
                  f"status={status} devs={[(d['id'], d['name'], d['online']) for d in devs]}")

            status, hdrs, body = await http(s, "GET", "/api/status")
            st = json.loads(body)
            check("GET /api/status has devices + devices_online",
                  status == 200 and isinstance(st.get("devices"), list)
                  and st.get("devices_online", 0) >= 1
                  and any(d.get("id") == DEV_A for d in st.get("devices", [])),
                  f"status={status} online={st.get('devices_online')}")

            # ---- 4. device B: per-device targeting ----------------------------
            async with websockets.connect(f"{WS}/ws?token={TOKEN}") as dev_b:
                await dev_b.send(json.dumps({"type": "device_online", "ts": 1700000003000,
                                             "payload": {"id": DEV_B, "name": DEV_B_NAME, "url": "test"}}))

                status, hdrs, body = await http(s, "GET", "/api/devices")
                devs = json.loads(body).get("devices", [])
                dev_b_rec = next((d for d in devs if d["id"] == DEV_B), None)
                check("GET /api/devices lists device B too",
                      status == 200 and dev_b_rec is not None and dev_b_rec.get("online") is True,
                      f"devs={[(d['id'], d['name'], d['online']) for d in devs]}")

                # command addressed to A must reach A and NOT B
                async with websockets.connect(f"{WS}/ws/ui",
                                              additional_headers={"Cookie": SESS_COOKIE}) as ui2:
                    await ui2.recv()  # hello
                    await ui2.recv()  # status
                    await ui2.send(json.dumps({"type": "capture", "to": DEV_A}))
                    got_a = json.loads(await asyncio.wait_for(dev_a.recv(), timeout=3))
                    check("Command with to=dev-a forwarded to A", got_a.get("type") == "capture", f"{got_a}")
                    await asyncio.wait_for(ui2.recv(), timeout=3)  # cmd_accepted
                    try:
                        leaked = await asyncio.wait_for(dev_b.recv(), timeout=1.5)
                        check("Command to A does NOT reach B", False, f"B received {leaked}")
                    except asyncio.TimeoutError:
                        check("Command to A does NOT reach B", True, "B silent (correct)")

                    await ui2.send(json.dumps({"type": "mic_start", "to": DEV_B}))
                    got_b = json.loads(await asyncio.wait_for(dev_b.recv(), timeout=3))
                    check("Command with to=dev-b forwarded to B", got_b.get("type") == "mic_start", f"{got_b}")
                    await asyncio.wait_for(ui2.recv(), timeout=3)  # cmd_accepted

        # ---- 5. per-device offline queue + flush on reconnect -----------------
        async with websockets.connect(f"{WS}/ws/ui",
                                      additional_headers={"Cookie": SESS_COOKIE}) as ui3:
            await ui3.recv()  # hello
            st2 = json.loads(await ui3.recv())
            check("UI WS status device offline", st2.get("type") == "status" and st2.get("device_online") is False,
                  f"{st2}")
            await ui3.send(json.dumps({"type": "mic_start", "to": DEV_A}))
            qa = json.loads(await asyncio.wait_for(ui3.recv(), timeout=3))
            check("UI command queued while device offline", qa.get("type") == "cmd_accepted" and qa.get("queued") is True,
                  f"{qa}")

            async with websockets.connect(f"{WS}/ws?token={TOKEN}") as dev_a2:
                await dev_a2.send(json.dumps({"type": "device_online", "ts": 1700000005000,
                                              "payload": {"id": DEV_A, "name": DEV_A_NAME, "url": "test"}}))
                flushed = json.loads(await asyncio.wait_for(dev_a2.recv(), timeout=3))
                check("Queued command flushed on device A reconnect", flushed.get("type") == "mic_start", f"{flushed}")

        # ---- 6. media listing + bytes + dev tag --------------------------------
        status, hdrs, body = await http(s, "GET", "/api/files?kind=img")
        files = json.loads(body)
        check("Screenshot indexed in /api/files", status == 200 and len(files) == base_img + 1
              and files[0]["kind"] == "img",
              f"status={status} files={[(f['name'], f['kind'], f['size']) for f in files]}")

        status, hdrs, body = await http(s, "GET", files[0]["url"])
        check("Screenshot bytes served via /media/img", status == 200 and body == tiny_jpg,
              f"status={status} len={len(body)}")

        shot = files[0]  # api_files sorts newest first
        check("Screenshot indexed with dev=dev-a tag",
              shot.get("dev") == DEV_A and shot.get("dev_name") == DEV_A_NAME,
              f"entry={shot}")

        status, hdrs, body = await http(s, "GET", f"/api/files?kind=img&dev={DEV_A}")
        dev_a_files = json.loads(body)
        status, hdrs, body = await http(s, "GET", f"/api/files?kind=img&dev={DEV_B}")
        dev_b_files = json.loads(body)
        check("GET /api/files?dev= filters per device",
              status == 200 and any(f.get("dev") == DEV_A for f in dev_a_files)
              and all(f.get("dev") != DEV_A for f in dev_b_files),
              f"dev-a={len(dev_a_files)} dev-b={len(dev_b_files)}")

        status, hdrs, body = await http(s, "GET", "/api/locations")
        locs = json.loads(body)
        check("Location recorded + last set", status == 200
              and locs.get("locations") and locs["locations"][0]["lat"] == 12.9716
              and locs["last"]["lon"] == 77.5946, f"status={status} locs={locs['locations']}")

        status, hdrs, body = await http(s, "GET", "/api/events")
        evs = json.loads(body)
        types = [e["type"] for e in evs]
        check("Event ring has device_online/screenshot/location/ack",
              all(t in types for t in ("device_online", "screenshot", "location", "ack")), f"types={types}")

        status, hdrs, body = await http(s, "GET", "/media/img/nope.png")
        check("Missing media -> 404", status == 404, f"status={status}")

        # ---- 7. multipart upload, parts in awkward order (file->type->name) ---
        async def upload(kind: str, fname: str, content: bytes, dev: str | None = None) -> tuple:
            fd = aiohttp.FormData()
            fd.add_field("file", content, filename=fname, content_type="application/octet-stream")
            fd.add_field("type", kind)
            fd.add_field("name", fname)
            if dev:
                fd.add_field("dev", dev)
            return await http(s, "POST", f"/upload?token={TOKEN}", data=fd)

        fake_mp4 = b"\x00\x00\x00\x18ftypmp42" + b"A" * 2048
        fake_wav = b"RIFF\x24\x00\x00\x00WAVEfmt " + b"\x10\x00\x00\x00" + b"\x01\x00\x01\x00" + b"\x00\x00\x00\x00" + b"\x00\x00\x00\x00" + b"\x00\x00\x00\x00" + b"data\x00\x00\x00\x00"

        status, hdrs, body = await http(s, "POST", f"/upload?token={TOKEN}",
                                        data={"type": "video", "name": "clip.mp4", "file": fake_mp4})
        check("Upload video (form order type,name,file)", status == 200 and json.loads(body).get("ok") is True,
              f"status={status} body={body[:100]}")

        status, hdrs, body = await upload("video", "weird_order.mp4", fake_mp4)
        check("Upload video with file-before-type parts", status == 200 and json.loads(body).get("ok") is True,
              f"status={status} body={body[:100]}")

        status, hdrs, body = await upload("audio", "note.wav", fake_wav)
        check("Upload audio with file-before-type parts", status == 200 and json.loads(body).get("ok") is True,
              f"status={status} body={body[:100]}")

        status, hdrs, body = await upload("video", "from_b.mp4", fake_mp4, dev=DEV_B)
        check("Upload with dev=dev-b part accepted + tagged",
              status == 200 and json.loads(body).get("ok") is True
              and json.loads(body).get("file", {}).get("dev") == DEV_B,
              f"status={status} body={body[:120]}")

        status, hdrs, body = await upload("image", "evil.png", b"x")
        check("Upload unknown type -> 400", status == 400, f"status={status} body={body[:80]}")

        status, hdrs, body = await http(s, "POST", "/upload", cookie=False,
                                        data={"type": "video", "name": "x.mp4", "file": b"x"})
        check("Upload without token -> 401", status == 401, f"status={status} body={body[:80]}")

        status, hdrs, body = await http(s, "GET", "/api/files?kind=video")
        vids = json.loads(body)
        check("Videos indexed (3)", status == 200 and len(vids) == base_video + 3,
              f"vids={[(v['name'], v['size']) for v in vids]}")
        status, hdrs, body = await http(s, "GET", vids[0]["url"])
        check("Video bytes served", status == 200 and len(body) == len(fake_mp4), f"status={status} len={len(body)}")

        status, hdrs, body = await http(s, "GET", "/api/files?kind=audio")
        auds = json.loads(body)
        check("Audio indexed (1)", status == 200 and len(auds) == base_audio + 1
              and auds[-1]["name"].endswith(".wav"),
              f"auds={[(a['name'], a['size']) for a in auds]}")

        status, hdrs, body = await http(s, "GET", f"/api/files?dev={DEV_B}")
        dev_b_all = json.loads(body)
        check("GET /api/files?dev=dev-b returns B's upload",
              status == 200 and any("from_b" in f.get("name", "") for f in dev_b_all),
              f"dev-b files={[(f['name'], f.get('dev')) for f in dev_b_all]}")

        status, hdrs, body = await http(s, "GET", "/api/status")
        st = json.loads(body)
        check("GET /api/status shape", st.get("token_locked") is True and "uptime_s" in st, f"{st}")

    failed = [n for n, ok in RESULTS if ok is False]
    skipped = [n for n, ok in RESULTS if ok is None]
    print("\n==== RESULT ====")
    print(f"{(len(RESULTS) - len(failed) - len(skipped))}/{len(RESULTS) - len(skipped)} checks passed"
          + (f" ({len(skipped)} skipped: pre-existing media state)" if skipped else ""))
    if failed:
        print("FAILED:", failed)
        return 1
    print("ALL_CHECKS_OK")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
