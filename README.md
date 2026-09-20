# PersonalRecorder — Android Capture Lab (consent-based, fully visible)

A small Android app you build, install, and run **on your own device** to learn how
modern Android apps legitimately capture screen, microphone, and location data.
Everything is visible and consent-based: the app has a launcher icon, shows a
persistent notification while any capture service is running, and asks for every
permission through the standard Android dialogs.

> **What this is NOT:** a covert or stealth tool. There is no icon hiding, no
> notification suppression, no call interception, and no persistence tricks.
> Those are not possible without device-owner/root privileges anyway — and this
> lab deliberately stays on public, declarable APIs so you can read every line.

---

## Feature table

| Feature | Implementation | Permission/consent |
|---|---|---|
| Screen capture (auto, every 2 s) | `ScreenCaptureService` + `MediaProjection` + `ImageReader` (RGBA_8888) | `MediaProjection` consent dialog (re-granted on every launch) |
| Full-resolution PNG saved locally | `internal storage/PersonalRecorder/shots/*.png` | — |
| Wire payload (≈720 px wide, JPEG/PNG base64) | sent to your relay over WebSocket | Internet |
| Frame-diff capture | 48 px-wide preview signature; frame forwarded only when ≥ 0.5 % of preview pixels changed | — |
| Battery-aware pause | `ACTION_BATTERY_CHANGED` receiver; auto-capture, diff, and reporting pause at ≤ 15 % when not charging; resume on charge or on forced `capture` | — |
| Screenshot on demand | `capture` command (force=true bypasses diff + battery pause) | — |
| Ambient audio by request | `AudioRecorderService` + `MediaRecorder` (AAC/m4a, saved to internal storage) | `RECORD_AUDIO` runtime permission |
| Screen + mic recording (MP4) | `ScreenAudioRecorderService`: `MediaRecorder` (SURFACE video + MIC audio, H.264/AAC) from a MediaProjection virtual display | `MediaProjection` consent dialog + `RECORD_AUDIO` |
| Device media audio capture (WAV) | `DeviceAudioService`: `AudioPlaybackCapture` (API 29+) of `USAGE_MEDIA`/`USAGE_GAME` streams → 44.1 kHz mono PCM WAV | `MediaProjection` consent dialog (required by the platform even though no pixels are captured) |
| Live location by request | `LocationService` + `LocationManager` (GPS + network, 5000 ms updates) | `ACCESS_FINE/COARSE_LOCATION` runtime permission |
| Command channel (steady mode) | OkHttp WebSocket: jittered exponential backoff (1–30 s), instant reconnect on Wi-Fi ↔ mobile-data hand-off, keep-alive foreground service + boot restore, command acks, offline command queue (300 s TTL on relay) | Internet + foreground-service notification |
| TLS transport (wss) | OkHttp client trusting only your self-signed CA (`assets/ca.pem`, keystore alias `relay_ca`) | — |
| Browser dashboard | `server/dashboard.py` (aiohttp): live event log, screenshot gallery, video/audio players, canvas position plot, command panel — no terminal needed on the monitoring side | `--token` password (HMAC session cookie) |
| Recording auto-upload | `MediaUploader` queues finished MP4/AAC/WAV files to the dashboard (`/upload`, 3 tries, 2 s/4 s backoff); URL derived from the live WS link so host/port/token always match | — |
| Video-call recording (m9) | `call_video_start`/`call_video_stop` → `ScreenAudioRecorderService` screen + own-mic MP4, tagged `category=call_video`; lands in the dashboard **Video calls** tab. One-time `MediaProjection` consent; the service stays in a standby session so repeated remote start/stop works without re-consent | `MediaProjection` consent dialog + `RECORD_AUDIO` |
| Audio-call recording (m9) | `call_audio_start`/`call_audio_stop` → `AudioRecorderService` mic-only M4A, tagged `category=call_audio`; lands in the dashboard **Audio calls** tab. Works fully remotely (no consent dialog) | `RECORD_AUDIO` runtime permission |
| Silent auto-update (m8) | `UpdateChecker` polls GitHub Releases on launch/link start, downloads the APK, verifies the SHA-256 from the release notes, and installs — silent when device-owner enrolled, one-tap system prompt otherwise | `REQUEST_INSTALL_PACKAGES`; device-owner enrollment is a documented adb step |

All runtime permissions are requested from the UI with `ActivityResultContracts`
— nothing is requested silently.

---

## Project layout

```
app/src/main/java/com/example/personalrecorder/
├── MainActivity.kt            # UI, permission flow, MediaProjection consent, steady-link start, URL list input
├── BootReceiver.kt            # restores the steady link after device reboot
├── services/ (in app module, see below)
├── net/
│   ├── RealtimeClient.kt      # WS client: backoff reconnect among a failover URL list, ping, acks,
│   │                          #   TLS (assets/ca.pem), per-install device id/name, upload-URL derivation
│   ├── MediaUploader.kt       # uploads finished recordings to the dashboard /upload (retry + failure event)
│   └── CommandBus.kt          # in-app command routing (command -> service action)
└── services/
    ├── ScreenCaptureService.kt     # 2 s capture loop, frame-diff, battery pause
    ├── AudioRecorderService.kt     # ambient audio via MediaRecorder
    ├── ScreenAudioRecorderService.kt # screen + mic -> MP4 (MediaRecorder SURFACE)
    ├── DeviceAudioService.kt       # device media audio -> WAV (AudioPlaybackCapture)
    ├── ConnectionService.kt        # steady-mode keep-alive FGS + network-change reconnect
    └── LocationService.kt          # periodic GPS/network location
server/
├── dashboard.py      # browser dashboard: live log, gallery, map, multi-device registry, commands, uploads
├── relay_server.py   # WS/wss relay: saves screenshots + prints events (terminal-only fallback)
├── send_command.py   # push a command at a connected app
├── requirements.txt  # aiohttp (the only server dependency)
└── gen_cert.sh       # self-signed CA + server cert for wss
deploy/
├── Dockerfile · entrypoint.sh · docker-compose.yml · .env.example    # container hosting
├── render.yaml       # Render Blueprint (free web service + optional disk)
├── systemd/          # personalrecorder-dashboard.service + dashboard.env.example (VPS)
├── backup.sh · restore.sh             # data backups + disaster recovery
└── ../.github/workflows/ci.yml        # CI: server e2e harness, GHCR image, on-demand APK
gradlew · gradle/     # Gradle wrapper 8.13 — build the APK without installing Gradle
harness/poc_dash.py   # end-to-end smoke test (37 checks, incl. multi-device flows)
```

## Wire protocol

Server (or your own client) → app commands:

Every command may carry `"to": "<device-id>"` to address one phone; without
`to` the dashboard targets the only known device (or the shared `default`
bucket when no device announced yet).

| Command | Effect |
|---|---|
| `capture` | Force a screenshot immediately, bypassing diff + battery pause |
| `mic_start` / `mic_stop` | Start/stop ambient audio recording |
| `location_start` / `location_stop` | Start/stop the location stream |
| `record_start` / `record_stop` | Start/stop screen + mic recording to MP4 |
| `deviceaudio_start` / `deviceaudio_stop` | Start/stop device media-audio capture to WAV (API 29+) |

App → server events (JSON `{"type": ..., "ts": ..., "payload": {...}}`):

| Event | Payload |
|---|---|
| `screenshot` | `data` (base64), `width`, `height`, `path` (local file) |
| `location` | `lat`, `lon`, `accuracy` |
| `capture_started` | duration/battery info |
| `capture_paused` / `capture_resumed` | battery %, reason |
| `screenshot_skipped` | `reason` (diff/battery/fps-guard), `skipped` (sent every 10 skips) |
| `audio_started` / `audio_stopped` | file path, format |
| `recording_started` / `recording_stopped` / `recording_error` | file path, resolution, error reason |
| `deviceaudio_started` / `deviceaudio_stopped` / `deviceaudio_error` | file path, sample rate, error reason |
| `device_online` | sent once on WS open with `payload: {id, name, url}`: registers the phone in the dashboard's device list, marks its socket online and flushes commands queued while it was offline |
| `media_upload_failed` | `path`, `kind`, `reason`, `attempts` — a finished recording could not be delivered to the dashboard |
| `ack` | `cmd` echoed: the app received the command from the relay (the relay prints `cmd_accepted` next to the commander's request) |

---

## Build (Android Studio)

1. Open this folder in **Android Studio** (Ladybug or newer).
2. Let Gradle sync. Requirements: JDK 17, `compileSdk/targetSdk 34`,
   `minSdk 26`, AGP 8.5.2, Kotlin 2.0.20.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or from a
   terminal: `./gradlew assembleDebug` (the checked-in Gradle wrapper
   8.13 downloads everything else; the APK lands in
   `app/build/outputs/apk/debug/`). GitHub Actions can also build it
   on demand — see “Hosting for free with GitHub”.
4. (Optional but recommended) run the end-to-end harness against the
   dashboard before shipping anything:
   `pip install aiohttp websockets && python3 harness/poc_dash.py` after
   starting `server/dashboard.py --port 8899 --token dashsecret`.
5. Install on your own phone: copy the APK over and tap it. First launch:
   - grant notifications, microphone and location when prompted,
   - tap **Start screen capture** and confirm the MediaProjection dialog,
   - enter your relay URL and tap **Connect**.

The notification stays visible while capture, audio, or location runs — that is
intentional and required by Android 14+ foreground-service rules.

## Run the relay (computer on the same network)

```bash
cd server
pip install websockets           # or: pip3 install websockets
python3 relay_server.py --host 0.0.0.0 --port 8765 --out ./relay_out
```

On the phone, connect to `ws://<computer-ip>:8765` (find the IP with
`ipconfig getifaddr en0` on a Mac). Press **Connect** in the app: the status
line should say `WS open: ws://…`.

Send commands from another terminal while the app is connected:

```bash
python3 send_command.py capture            # forced screenshot now
python3 send_command.py mic_start          # ambient audio
python3 send_command.py mic_stop
python3 send_command.py location_start     # stream location every 5 s
python3 send_command.py location_stop
python3 send_command.py record_start       # screen + mic -> MP4
python3 send_command.py record_stop
python3 send_command.py deviceaudio_start  # device media audio -> WAV (API 29+)
python3 send_command.py deviceaudio_stop
# default URL is ws://127.0.0.1:8765 — override with --url if needed
```

Screenshots land in `relay_out/shot_<ts>.jpg`; PNG originals are also saved on
the phone under internal storage `PersonalRecorder/shots/`.

> Terminal-only fallback: `relay_server.py` prints events and saves files.
> Want a website instead — live map, media players, click-to-command?
> Use the [Dashboard](#dashboard--the-browser-monitor) below; same protocol.

---

## Dashboard — the browser monitor

`server/dashboard.py` speaks the same WS protocol as the relay, but wraps it
in a web UI: no terminal, no `send_command.py`. It provides:

- **Live event log** — every `screenshot`, `location`, and service event the
  app sends, streamed to open browser tabs over `/ws/ui` (history via
  `/api/events`).
- **Screenshot gallery** — the dashboard saves each wire screenshot to its
  data folder (`<out>/media/img/…`) and renders them in the **Shots** tab;
  originals are also kept in the browser-friendly `media/` tree.
- **Media players** — uploaded MP4 (screen + mic) and AAC/WAV audio play
  inline in the **Media** tab from `/media/video/…` and `/media/audio/…`.
- **Position plot** — live `location` events drawn on a canvas from
  `/api/locations`.
- **Command panel** — buttons for `capture`, `mic`, `location`, `record`,
  and `deviceaudio`; commands go over the same device websocket as the relay,
  including the offline queue (300 s TTL), acks, and reconnect flush.
- **Devices tab** — every phone that announces itself (`device_online`) is
  listed with its id, name, and online/offline state; pick one to target
  commands at it specifically.
- **Per-device targeting** — commands carry `"to": "<device-id>"`; media
  uploads are tagged with the device id (`/upload?dev=…`) and filterable in
  the gallery and media tabs.

Run it (it needs aiohttp):

```bash
cd server
pip install aiohttp            # or: pip3 install aiohttp
python3 dashboard.py --host 0.0.0.0 --port 8899 --token change-me
```

- `--token` protects the web UI (HMAC session cookie via the login page) and
  `POST /upload`. Without a token the dashboard is open; with one, app
  uploads must carry it.
- For the app to upload recordings, give it a token-carrying relay URL, e.g.
  `ws://<ip>:8899?token=change-me`. The app's `uploadUrl()` derives the
  media URL from the live WS link and keeps the query string, so finished
  MP4/AAC/WAV files land at `http://<ip>:8899/upload?token=change-me`
  automatically.
- Use a different port from the relay (8899 here) if you want both side by
  side; the dashboard alone is enough if you prefer a browser over the
  terminal, and `--out` controls where screenshots and uploads are stored.

### Deploying beyond the LAN

The dashboard accepts the same `--cert`/`--key` flags as the relay and serves
`wss://` directly with them. The simpler path is a TLS reverse proxy (Caddy,
nginx, etc.) that terminates `wss://` and proxies `/` plus websocket upgrades
to `127.0.0.1:8899`. The app then connects to `wss://your-host` and its
recordings upload to `https://your-host/upload` automatically, because
`uploadUrl()` is derived from the live WS link. Combine this with the
steady-mode notes below and the phone can report from any network.

### Multi-device dashboard

The dashboard is multi-device by default — no extra configuration. Each app
instance generates a stable install id (`device_id`) and announces itself
with `device_online` the moment its websocket opens:

- **Registry** — `devices.json` in the data folder (`--out`) records every
  device that ever announced: `{id, name, first_seen, last_seen}`. The
  **Devices** tab shows the live list with online/offline state.
- **Per-device queues** — commands sent while a device is offline are queued
  per device (300 s TTL) and flushed when that device reconnects.
- **Targeting** — the command panel targets the selected device; the CLI
  `send_command.py` accepts `--to <device-id>`. Commands without `to` go to
  the only known device, or to the shared `default` bucket before any device
  has announced.
- **Tagged media** — the app appends `&dev=<device-id>` to its upload URL, so
  every screenshot/MP4/AAC/WAV is stored under `media/<type>/<device-id>/…`
  and filterable in the gallery and media tabs.
- **Failover URLs** — the app accepts a comma-separated list of relay URLs
  (e.g. `ws://lan-ip:8899?token=…,wss://public-host?token=…`). It walks the
  list in order, reconnects to the next URL on failure, and remembers the
  working one for the session. This is how a phone can prefer the LAN
  dashboard and fall back to the public one when away from home.

### Hosting for free with GitHub

GitHub is free for public repos and gives you three pieces of this project:
the repo itself, CI (Actions) that tests the server and builds the APK, and
a container registry (GHCR) that can feed a free-tier host. The dashboard
itself is a single Python file with one dependency (`aiohttp`), so it runs
anywhere Python 3.9+ runs.

**1. Push the repo**

```bash
git init && git add . && git commit -m "PersonalRecorder lab"
gh repo create personalrecorder --public --source=. --push   # or create it on github.com and push
```

**2. Let CI do the work** — `.github/workflows/ci.yml` runs on every push:

- `server-check`: compiles `dashboard.py`, starts it on a random port with a
  test token, and runs the end-to-end harness (`harness/poc_dash.py`, 37
  checks incl. multi-device flows) against it.
- `docker`: builds the image and pushes it to `ghcr.io/<you>/personalrecorder`
  on `main` and tags (needs `packages: write` — enabled by default for the
  repo owner).
- `apk`: manual only (`workflow_dispatch`) — click **Actions → apk → Run
  workflow** to build `app-debug.apk` in CI and download it from the
  artifacts. No local Android SDK needed.

**3. Deploy the dashboard for free**

- **Render (easiest)**: `deploy/render.yaml` is a Blueprint. On
  render.com → **New → Blueprint**, paste the repo URL; set the
  `DASH_TOKEN` environment variable (your secret) and a disk mount at
  `/var/data` if you want uploads to survive restarts. Render gives you a
  `https://<name>.onrender.com` URL with TLS — the app connects to
  `wss://<name>.onrender.com?token=…` and uploads to
  `https://<name>.onrender.com/upload?token=…` automatically.
  Free-tier note: the service sleeps when idle and the filesystem is
  ephemeral — use the disk mount and the backup script below.
- **Any VPS / free tier (Oracle, AWS, etc.)**: `deploy/systemd/` gives you a
  hardened unit file; `deploy/docker-compose.yml` gives you a one-command
  container (`docker compose up -d`). Both read `DASH_TOKEN` from an env
  file.
- **GitHub Codespaces (dev only)**: a Codespace can run the dashboard for
  testing, but it is not a public host — use Render/VPS for real devices.

**4. Point the APK at it** — in the app's connect field enter
`wss://<your-host>?token=<DASH_TOKEN>` (or a comma-separated failover list
with your LAN dashboard first). Install the APK, press Connect, and the
phone appears in the **Devices** tab. That's the whole loop: install → it
announces itself → you request any feature from the dashboard.

### Auto-update — push a new APK, phones install it in the background

Milestone m8 wires the app to GitHub Releases so "ship an update" is just
*bump the versionCode and push*. On every app launch and every steady-link
start, `UpdateChecker` asks the GitHub API for the latest release, compares
the tag (`v<versionCode>`) with the installed version, downloads the APK,
verifies its SHA-256 against the hash CI publishes in the release notes, and
installs it.

**One-time setup (two steps):**

1. **Point the app at your repo** — in
   `app/src/main/java/com/example/personalrecorder/net/UpdateChecker.kt` set
   `UPDATE_REPO` to your GitHub account, e.g. `alice/PersonalRecorder`.
   Until you do, the check logs "not configured" and skips (no crash).
2. **Add the `DEBUG_KEYSTORE_B64` secret** — GitHub → repo → **Settings →
   Secrets and variables → Actions → New repository secret**. Value: the
   base64 of your local debug keystore, so CI-built APKs carry the *same
   signature* as your local ones (an update over an installed copy fails the
   signature check otherwise):

   ```bash
   base64 < ~/.android/debug.keystore | pbcopy   # macOS
   base64 < ~/.android/debug.keystore | xclip -selection clipboard   # Linux
   ```

**The push-to-update loop:**

1. Bump `versionCode` in `app/build.gradle.kts` (e.g. `1` → `2`).
2. `git add -A && git commit -m "v2" && git push`.
3. CI (`release` job) builds the debug APK, computes its SHA-256, and
   creates a GitHub Release tagged `v2` with the hash in the notes.
4. Phones check on next launch / link start, download `v2`, verify the hash,
   and install.

**How the install happens:**

- **Silent (no UI at all)** — only when the app is the device owner. That is
  a deliberate, documented adb step on a phone you own:

  ```bash
  adb shell dpm set-device-owner com.example.personalrecorder/.AdminReceiver
  ```

  The `AdminReceiver` declares no policies; it exists so the platform
  recognizes the app as a device-owner candidate, which makes
  `PackageInstaller` sessions run without any prompt.
- **One-tap fallback (stock phone)** — without device-owner enrollment the
  app opens the normal system installer and you tap **Install** once. This
  is the only public-API path on a stock phone; the app stays fully visible
  and consent-based either way.

**Honest limits:** the update must be signed with the same key as the
installed copy (hence the keystore secret); GitHub's API is rate-limited for
unauthenticated requests (60/hour/IP — plenty for a lab); and on a stock,
non-device-owner phone the one tap is unavoidable by platform design.

## Call recording — video calls & audio calls (m9)

Two dedicated commands record calls and file them into their own dashboard
sections, for any calling app (WhatsApp, Telegram, Meet, Zoom, dialer, …):

| Command | What it records | Where it lands |
|---|---|---|
| `call_video_start` / `call_video_stop` | Screen + the phone's own mic (MP4, H.264/AAC) | dashboard **Video calls** tab (`category=call_video`) |
| `call_audio_start` / `call_audio_stop` | The phone's own mic only (M4A, AAC) | dashboard **Audio calls** tab (`category=call_audio`) |

Both are available from the dashboard command bar, the Devices tab quick
buttons, and `send_command.py`:

    python3 send_command.py call_video_start
    python3 send_command.py call_audio_start

### How the video-call session works

- The first `call_video_start` opens the one-time `MediaProjection` consent
  dialog on the phone (Android policy — it cannot be skipped).
- After consent the service enters a **standby session**: the projection and
  the foreground service stay alive, so you can start/stop recordings
  remotely as many times as you like without touching the phone again.
- If a `call_video_start` arrives while no projection is active, the app
  replies with a `call_video_consent_needed` event (`reason:
  screen_capture_consent_required`) so the dashboard log tells you to tap
  Start on the phone once.
- `call_video_stop` finishes the MP4 and uploads it; `call_audio_stop` does
  the same for the M4A. Both are tagged with the device id and the category,
  and the dashboard refreshes the matching tab live.

### Honest platform limits (why far-end audio is not in the file)

Stock Android gives no public API to capture the *other* side of a call:

- During a call the mic is exclusive to the calling app, so the MP4/M4A
  audio track captures the phone's own mic (your side of the room), not the
  far-end voice.
- `AudioPlaybackCapture` (API 29+) explicitly excludes
  `USAGE_VOICE_COMMUNICATION` — the usage class of WhatsApp/dialer calls —
  so `DeviceAudioService` yields silence during calls (it still works for
  music/games/media).
- `CAPTURE_AUDIO_OUTPUT` (the only way to record the far end) is a
  signature/privileged permission for system apps only.

So the honest public-API maximum is: **video call = screen + own mic,
audio call = own mic**. Root/Xposed/system-signature builds could go further,
but those are out of scope for this learning lab.

### Backups & recovery

Everything the dashboard knows lives in its data folder (`--out`):
`devices.json` (registry), `db.json` (events), `locations.jsonl`, and
`media/` (screenshots, MP4, AAC/WAV). Backing up that one folder backs up
the whole dashboard.

- **`deploy/backup.sh`** — tars the data folder (plus
  `/etc/personalrecorder/certs` if present) into
  `backups/personalrecorder-<date>.tar.gz` and keeps the last `KEEP` (default
  7). Run it from cron/systemd timer, or from the host's scheduler:
  `0 3 * * * /path/to/deploy/backup.sh`.
- **`deploy/restore.sh`** — finds the newest backup, extracts it, and
  restores the data folder. Point it at a fresh dashboard install and you
  get the registry, event history, and all media back.
- **Docker**: mount a named volume for the data dir (`docker compose` does
  this) and back up the volume with the same script.
- **Render**: with a disk mounted at `/var/data`, run `backup.sh` on a
  schedule (or before deploys) and download the tarball; restore onto any
  new host with `restore.sh`.

Recovery drill (5 minutes): start a fresh dashboard on a new machine with
the same `--token`, run `restore.sh`, and confirm the **Devices** tab shows
the same phones and the gallery still plays old media. The phones reconnect
on their own via the failover URL list.

---

## TLS (wss) with a self-signed CA — exercise 3

1. Generate a CA + server cert for your relay machine's IP (or hostname):

```bash
cd server
chmod +x gen_cert.sh
./gen_cert.sh 192.168.1.23          # use your computer's LAN IP
# or: ./gen_cert.sh mylab.local out
```

2. Make the CA trusted by the app at build time:

```bash
cp certs/ca.pem ../app/src/main/assets/ca.pem   # creates app/src/main/assets/
```

   The app's `buildTlsClient()` reads `assets/ca.pem` and builds a TLS client
   that trusts **only that CA** (keystore alias `relay_ca`). If `ca.pem` is
   missing and you connect to a `wss://` URL, the app prints a status warning
   and falls back to the system trust store (fine for public certs such as
   Let's Encrypt; useless for your self-signed one).

3. Run the relay with TLS:

```bash
python3 relay_server.py --cert certs/server.pem --key certs/server.key --host 0.0.0.0 --port 8765
```

4. In the app, connect to `wss://192.168.1.23:8765`. On Android the target
   hostname must match the cert SAN (`IP:` entries are matched by IP SAN).

To test the client without the phone, or with `send_command.py`:

```bash
python3 send_command.py --url wss://192.168.1.23:8765 --insecure capture
```

(`--insecure` skips verification in the test client only — the Android app
never disables hostname/chain verification, by design.)

---

## Steady mode — keep the link up (even on another network)

Exercise 5 asked what a stale link costs you. Steady mode is the fix: once you
press **Connect**, the app keeps the relay link alive in the background —
through app relaunches, Wi-Fi ↔ mobile-data hand-offs, and even device
reboots — until you explicitly press **Disconnect**.

### What makes it steady

- **Keep-alive foreground service** (`ConnectionService`, type `specialUse`):
  the link is hosted in the process, not the activity, and the service is
  `START_STICKY` so the OS restarts it if it kills the process.
- **Jittered exponential backoff** in `RealtimeClient`: on failure the client
  retries at 1 s → 2 s → 4 s … capped at 30 s (+ 0–2 s jitter), so a dead
  network does not spam packets and a live one is found quickly.
- **Instant hand-off**: the service registers a `registerDefaultNetworkCallback`
  and calls `reconnectNow()` the moment Wi-Fi drops or mobile data comes up —
  no waiting for the 20 s ping timeout.
- **Auto-restore**: the relay URL is saved in `relay_prefs`. On app relaunch
  (`restoreSteadyLink`) and on boot (`BootReceiver`), the link comes back
  automatically — unless you pressed **Disconnect** (`user_disconnected=true`).
- **No lost commands**: while the phone is offline, the relay queues commands
  for you (300 s TTL) and flushes them on reconnect. `send_command.py` prints
  `queued — device offline; will be delivered on reconnect` or
  `delivered to device` depending on what happened.

### Different network — yes, it works

The phone does not need to be on the same Wi-Fi as your relay. The only thing
that must be reachable from the phone is the relay's address:

- **Same network (lab default):** `ws://<computer-ip>:8765` over LAN.
- **Different network:** put the relay on a small VPS or forward a port on
  your router, then run it with TLS and connect over `wss://` — exactly the
  exercise-3 flow, just with a public address.

```bash
# on the VPS / forwarded host
python3 relay_server.py --host 0.0.0.0 --port 8765 \
    --cert certs/server.pem --key certs/server.key --out ./relay_out

# phone connects to:  wss://your-vps-or-ddns:8765
```

### Honest limits of steady mode

- **Doze / App Standby**: on stock Android a long screen-off still pauses
  background network work despite the foreground service. Set the app to
  **Unrestricted** battery mode (Settings → Apps → PersonalRecorder →
  Battery) and charge while testing; a real deployment would add push
  (FCM) for screen-off command delivery.
- **Visible notification**: the steady link shows a persistent notification
  with a **Stop link** action. That is Android 14+ policy for foreground
  services — and this lab is deliberately visible anyway.
- **Single device per relay**: the terminal-only `relay_server.py` tracks one
  device per session; start a new instance per phone if you test several.
  The browser dashboard (`dashboard.py`) is multi-device — see the
  Multi-device dashboard section.
- **Queue TTL**: offline commands live 300 s (`PENDING_TTL_SECONDS` in
  `relay_server.py`), then they are dropped. Raise it if your scenario
  needs longer.

---

## Learning exercises

1. **Frame-diff**: change `DIFF_REQUIRED_CHANGE_FRACTION` (0.005 = 0.5 %) or
   `DIFF_PREVIEW_WIDTH` in `ScreenCaptureService.kt`, watch the
   `screenshot_skipped` events, and inspect the 48 px preview itself. What is
   the cost/benefit trade-off vs. sending every frame?
2. **Battery pause**: set `BATTERY_PAUSE_LEVEL` to e.g. 90, trigger a
   `capture_paused` event, then force a screenshot with `capture` and observe
   that forced captures bypass the pause.
3. **TLS**: run the full wss flow above and `tcpdump -A -i en0 port 8765`
   (or Wireshark) on the server — verify the screenshot payload is encrypted.
4. **Android 14 FGS rules**: stop the app's services while the screen is off
   and note what the OS does; read why `FOREGROUND_SERVICE_MEDIA_PROJECTION`
   etc. are declared in the manifest.
5. **Protocol**: write your own 30-line `send_command.py`-style client — or a
   fake server that pushes `capture` — and reason about what a malicious
   server could ask this consent-based app to do (and why the user must still
   approve every service at runtime).
6. **Device audio vs. call audio**: send `deviceaudio_start`, play something
   with media/game usage (YouTube, Spotify, a game) — the WAV captures it.
   Then start a WhatsApp voice call and record again: the WAV is **silence**,
   because `AudioPlaybackCapture` deliberately excludes
   `USAGE_VOICE_COMMUNICATION` (the usage class of VoIP calls). Same for
   `record_start` (screen+mic MP4) — during a call, the mic is exclusive to
   the calling app. This is the platform's design, not an app setting.
   Inspect the frames/events and the WAV header (44-byte RIFF) if you want to
   go deeper.
7. **Steady mode + offline queue**: connect, then toggle **Airplane mode** on
   the phone. Watch the app log show backoff retries and the relay print
   `device offline … commands will be queued`. While the phone is offline,
   run `python3 send_command.py location_start` — it returns `queued — device
   offline`. Switch Airplane mode off (or back to Wi-Fi) and watch the queue
   flush: the relay prints the forwarded command, the app sends an `ack`, and
   the phone starts reporting location. Then press **Disconnect**, kill the
   app from Recents, and notice the link does *not* return; reconnect and
   reboot the phone instead to watch `BootReceiver` restore the link. Read the
   Doze/App-Standby bullet below before judging battery behaviour.

## Known limits (documented on purpose)

- MediaProjection consent must be re-confirmed on every launch (Android policy
  on 14+; the lab re-uses the token for the whole session).
- One-click stealth install / icon hiding is **not possible** with public APIs,
  and this app does not try. See the README header.
- Steady mode is a foreground-service design, so a persistent notification
  stays visible while the link is up, and Doze can still delay packets when
  the screen has been off for a long time. `START_STICKY` + boot receiver
  cover process death and reboots, but not airplane-mode-less tunnels with
  no connectivity at all — the queue covers those for 300 s.
- Call recording ships in its honest public-API form (m9): video calls =
  screen + the phone's own mic (MP4, **Video calls** tab), audio calls =
  the phone's own mic only (M4A, **Audio calls** tab). The **far-end** side
  of a call cannot be captured on stock, non-rooted Android, and this lab
  deliberately stays on public APIs. Two platform mechanisms make it
  impossible:
  - **Mic exclusivity & restricted sources**: `VOICE_CALL`/`VOICE_UPLINK`/
    `VOICE_DOWNLINK` capture requires `CAPTURE_AUDIO_OUTPUT`, a
    signature/privileged permission granted only to system apps. During a
    call the mic is exclusive to the calling app, so ambient `MIC` recording
    (and the MP4's audio track) gets silence while a call is active.
  - **`AudioPlaybackCapture` explicitly excludes VoIP**: API 29+ playback
    capture filters out `USAGE_VOICE_COMMUNICATION`, the usage class of
    WhatsApp/dialer calls, and privacy-sensitive apps can additionally set a
    capture policy that encrypts their audio. Exercise 6 demonstrates the
    exclusion: the WAV is silent during a WhatsApp call, by design.
  What the lab *does* ship: video-call screen+mic MP4, audio-call mic M4A,
  ambient mic, device media-audio WAV (YouTube/music/games), screenshots,
  location, and a live command channel. Root/Xposed/system-signature builds
  could bypass these limits, but those are out of scope for this learning
  lab.
- The APK is built locally with the checked-in Gradle wrapper
  (`./gradlew :app:assembleDebug`, verified on macOS with JDK 21, AGP 8.5.2,
  compileSdk 34) and can be rebuilt on demand by GitHub Actions; the
  server-side harness (`harness/poc_dash.py`) runs in CI on every push.
- Auto-update needs the same signing key on both sides: CI restores your
  debug keystore from the `DEBUG_KEYSTORE_B64` secret so release APKs match
  your local signature. On a stock (non-device-owner) phone the install is a
  one-tap system prompt — the platform's only public-API path; only
  device-owner enrollment (`dpm set-device-owner`) makes it fully silent.

## Legal / ethics (tl;dr)

Use only on devices you own or are explicitly authorized to test. Notification
and permission prompts make every capture visible to the phone's user; using
this app against someone without their knowledge violates local laws and the
Terms of this lab. This project exists for learning Android's capture APIs.
