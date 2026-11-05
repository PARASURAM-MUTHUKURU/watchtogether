# WatchTogether — Android prototype v0.2

One Android phone selects a downloaded movie. A second Android phone streams it through a private internet room. The movie is not uploaded in full beforehand or stored on the relay's disk.

## What is implemented

- Native Kotlin app, Android 8.0+ (API 26), Media3 ExoPlayer.
- Android document picker; no broad storage permission.
- Two room roles: host and one guest, each authenticated by a separate random bearer session token.
- Creator-key protection, hashed room credentials, ten-minute single-use invites, 12-hour room expiry, host revocation, payload limits and throttling. See [SECURITY.md](SECURITY.md).
- Shareable invite containing server address, room and guest credential.
- HTTPS/WSS relay architecture that needs no inbound connection to either phone.
- Bounded 256 KiB source reads and HTTP byte ranges for streaming and seeking.
- 64-bit file size and offset handling. No configured total movie-size cap, up to signed 64-bit byte counts. Rooms expire after 12 hours. Actual compatibility depends on file provider, container, codecs and devices.
- Shared Play, Pause and ±30-second seeking; host sends timeline updates every two seconds. Clients correct drift greater than one second. This is approximate synchronization, not frame accuracy.
- Readiness coordination pauses playback when either player buffers and resumes when both become ready, if playback was requested.
- Leaving as host closes the room. Leaving as guest pauses playback. The redeemed guest session credential permits reconnecting, but the current Android UI discards credentials on disconnect; create a new room to watch again.

## Important prototype boundaries

This is source code, not a verified installable APK. Server integration tests ran successfully; Android compilation and two-device playback have not been run in the build environment. Version 0.2 changes the join protocol; both phones and the server must use this release together.

Keep the app visible on both phones. It keeps the screen awake, but does not implement a background foreground-service player. Leaving the screen, device memory pressure or losing the network may interrupt sharing. Unexpected connection failure requires creating a new room; seamless recovery is not implemented. No transcoding, adaptive quality, voice chat, subtitles UI or DRM-service integration is included.

Use a locally stored, seekable video. Cloud document providers and files whose size is unknown may fail selection. MP4 with H.264 video and AAC audio is the recommended first test; codec support varies across Android devices. Protected downloads in subscription streaming apps cannot be selected.

There is no automatic bitrate reduction. The host's upload must sustain the original video bitrate. The prototype uses base64 over WSS for simplicity, adding approximately 33% to the host-to-relay video transfer. Small serialized requests also add latency; high-bitrate 4K video may need a binary/pipelined transport upgrade. A 2 GB file therefore needs roughly 2.67 GB host upload and 2 GB guest download, plus protocol overhead and rereads caused by seeking. The relay also incurs traffic charges. Memory is bounded per stream, rather than by total movie size.

## 1. Run the relay

Use Python 3.12+:

```sh
cd server
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
# First set WATCHTOGETHER_CREATOR_KEY to your generated private secret.
python server.py
```

Before starting, generate a private key with `python -c 'import secrets; print(secrets.token_urlsafe(32))'` and configure the `WATCHTOGETHER_CREATOR_KEY` environment variable securely. Give this key only to the host. Startup fails without a sufficiently long key. See `server/.env.example` for names; the Python process does not auto-load .env files.

The process listens on loopback port 8080. Put it behind Caddy using the example `Caddyfile`, replacing the example hostname with a real domain whose DNS points to the server. Caddy must be installed separately. Open public ports 80/443 for certificate issuance and HTTPS; keep port 8080 private. The proxy must support WebSocket upgrades and long streaming connections. Example when running Caddy on the same host:

```sh
sudo caddy run --config Caddyfile
```

Alternatively build the Dockerfile and bind container port 8080 to loopback on the reverse proxy host. There is no deployed server supplied with this project.

The app requires a publicly reachable HTTPS server with a trusted certificate. Both phones must be able to access the same address. HTTP is deliberately disabled in the Android manifest. A local-only server address will not work for a friend in another location.

This release adds private-server room-creation authentication and application request limits. Before an unrestricted public service, add individual account authentication, per-user quotas, edge protection, abuse handling and deployment monitoring. Rooms live in process memory; run one server instance, and expect rooms to disappear on restart. A room without a host is cleaned up after ten minutes by a periodic task. All rooms expire after twelve hours. Unredeemed invites expire in ten minutes. The server caps active rooms at 100 and concurrent HTTP video streams at two per room; these are resource safeguards, not movie-size limits. Do not log invite tokens or Authorization headers.

## 2. Build the Android app

1. Install Android Studio, JDK 17, and Android SDK platform 35.
2. Open the `android` folder in Android Studio.
3. Sync and build the `app` module, or run from `android` with `./gradlew assembleDebug` (or `gradlew.bat assembleDebug` on Windows).
4. The debug APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.
5. Install the same APK on both phones. Android Studio can install directly on connected devices.

Pinned tooling: Android Gradle Plugin 8.9.1, Kotlin 2.1.10, Media3 1.6.1, OkHttp 4.12.0. These are reproducible project choices, not a claim to be the newest releases. Dependency resolution and SDK compatibility still require Android build verification.

## 3. Watch with a friend (3-Step Pairing)

When WatchTogether opens, choose whether you are **Hosting** or **Joining**:

### If you are the Host (I have the movie file):
1. **Step 1 · Select Video**: Tap **Browse & Pick Movie** to select your local movie file.
2. **Step 2 · Create Room**: Verify your server address and enter the **Host access key**, then tap **Create Room & Share Invite**. The system automatically generates a secure single-use invite and opens the Android share sheet.
3. **Step 3 · Pair & Watch**: Send the link to your friend via your preferred chat app. Once your friend connects and buffers, the setup cards cleanly collapse into full-screen playback with synchronized Play, Pause, and Seek controls!

### If you are the Friend / Guest:
1. **Step 1 · Friend's Invite**: Copy the invite link from your chat app. WatchTogether automatically detects the code from your clipboard (or tap **Paste from Clipboard**).
2. **Step 2 · Connect & Pair**: Tap **Connect & Pair** to redeem the invite and establish a secure real-time stream.
3. **Step 3 · Watch in Sync**: The movie streams directly from your friend with automatic drift correction, zero buffering seeks, and Picture-in-Picture (PiP) multitasking support.

Tap **Leave** at any time to return to the 3-step home screen.

First test a short sample over different networks, then test the full two-hour movie. Do not depend on a long session before checking your devices and hosting connection.

## Validation

```sh
python -m unittest discover -s server -v
```

Fifteen integration tests exercise real local HTTP/WebSocket connections. Security tests cover authorization, invitation replay/expiry, room revocation, credential isolation, concurrency, flooding and payload limits. Baseline tests cover: full multichunk transfer and byte ranges, simulated seeking past 4 GiB in a 9 GB source without allocating the source, bearer authentication, invalid input, two-person admission, synchronization, buffering pause/resume and host-disconnect cleanup.

These tests simulate the phone's movie-reader protocol. They do not establish Android compatibility, sustained throughput or two-hour session reliability.

Before calling a build ready: compile/install on two physical Android phones; check different mobile networks; verify file selection, video/audio decoding, initial buffering, play/pause, repeated seeking, a >4 GB file, an entire two-hour session, expired invites and interruptions. Measure upload, relay memory, data costs and battery temperature.

## Protocol and structure

- `POST /rooms`: creator-key Bearer authentication; JSON `{size: <integer bytes>, mime: "video/mp4"}` → host credential and guest invite.
- `POST /rooms/{code}/join`: invitation Bearer credential → single-use redemption and guest session token.
- `DELETE /rooms/{code}`: host Bearer credential → revoke room and close its sessions/reads.
- `GET /rooms/{code}/ws`: authenticated WebSocket. Client `control`, `ready`, host `tick`; server `state`. Relay requests `read` with offset/length; host returns base64 `chunk` with matching ID.
- `GET/HEAD /rooms/{code}/movie`: bearer-authenticated HTTP stream with single byte-range support, suitable for ExoPlayer.
- `GET /health`: process health.

Read [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) for architecture, lifecycle, data paths, capacity and failure handling, and [SECURITY.md](SECURITY.md) for the threat model and implemented controls.

`android/` contains the Android Studio project; `server/` contains the relay, tests, container and proxy configuration.

TLS encrypts traffic between each phone and the relay. This is not end-to-end encryption: the relay operator can access movie bytes while relaying them. Use only videos you have permission to share.
