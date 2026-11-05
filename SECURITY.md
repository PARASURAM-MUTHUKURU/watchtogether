# Security model — v0.2

## Implemented controls

| Threat | Implemented defense | Remaining boundary |
|---|---|---|
| Strangers creating relay rooms | Creator-key authentication; process refuses a missing/short startup key | Shared private-server key, not individual user identity; unsuitable for a public multi-user service |
| Guessing room access | Random 256-bit host/invite/session credentials; bearer headers; constant-time digest comparison | Stolen valid bearer credentials can be used until revocation/expiry |
| Invitation replay | Ten-minute single-use invite exchanged atomically for separate guest token | First holder to redeem wins; no identity-bound friend verification |
| Credential disclosure in room memory | SHA-256 digests stored for host, invite and guest capabilities | Server has transient plaintext when issuing/validating; creator configuration key stays in memory |
| Extra room participants | Per-role reservation before the async WebSocket handshake | A stolen guest token can reconnect when the guest role is free |
| Cross-room access | Every media, socket and revoke request resolves credentials against that room | No protection against a compromised relay operator |
| Stale access | Absolute 12-hour session deadline, ten-minute unattached-room cleanup, host DELETE/leave revocation | Housekeeping closes idle sockets within a ten-second sweep interval; request authorization checks expiry immediately |
| Flooding and memory exhaustion | Room/stream caps, bounded counters, HTTP/WS size limits and message/control throttles | Application limits do not replace DDoS protection or edge connection admission |
| Invalid protocol data | Role-specific messages, strict bool/integer validation, bounded positions, exact chunk lengths, invalid socket traffic closes with policy code | A permitted host can intentionally supply misleading movie content |
| Browser-origin requests | Any Origin header rejected; no CORS support, no cookies | Service is for native clients; Origin is not proof of identity |
| Network interception | Android requires trusted HTTPS/WSS; cleartext disabled; no custom trust bypass | Proxy termination and internal relay segment must be secured by the operator |
| Redirect credential leaks | OkHttp automatic redirects disabled for API, WSS and media HTTP requests | Android changes have not been compiled or device-verified here |
| Accidental persisted secrets | Key and invite fields disable view saving/autofill; credentials memory-only, cleared on leave; backups disabled | Shared messenger invite, clipboard, keyboard, compromised device or authorized screen recording remain outside app control |
| Media retention | Relay code writes no movie files; no-store and nosniff headers | OS swap, proxy buffering and infrastructure traces require operator configuration |

## Private-server provisioning

The server operator generates a random secret, places it in `WATCHTOGETHER_CREATOR_KEY`, and gives it privately to the person allowed to create rooms. The host enters it in the host-only password field. It is not included in an invite, hard-coded in the APK, or persisted by the app. The guest only needs the invitation. This credential authorizes room creation on that server; it is not an account login system.

Use `python -c 'import secrets; print(secrets.token_urlsafe(32))'` to generate a key locally. Store it in a secret manager or protected environment file outside the project. Do not commit it. Changing this startup key requires a process restart, which also revokes all existing in-memory rooms. Room tokens are distinct from the creator key and are not accepted for room creation.

Invites are bearer secrets. Redemption checks expiration, consumes the invitation before any further await, and returns a new guest session token. Original invites cannot stream movies or reconnect sockets. Protect invitation messages and verify the recipient yourself. Automatic friend identity confirmation is not implemented.

## Trust boundaries

Both phone-to-proxy connections use TLS. The relay can read, copy or modify movie bytes and timeline state, and its operator must be trusted. This version does **not** implement end-to-end encryption. A permitted viewer can retain received content. Screenshot blocking would not establish a DRM guarantee and is not included.

All authorization happens server-side; hiding controls in the Android UI is not an authorization boundary. Neither invitation contents nor client-forwarded IP headers are trusted. Behind one Caddy instance, backend request buckets are shared by its connection peer; apply per-client limits at the trusted edge when serving more than this private use case. Health is not app-throttled to keep liveness probes usable; protect it with edge limits too.

The relay binds loopback by default. The container explicitly binds its internal network interface; publish only `127.0.0.1:8080:8080` when Caddy runs on the same machine. Never expose plaintext port 8080 publicly. TLS is a deployment prerequisite, not something the Python process enforces itself.

## Validation and release status

The integration suite exercises unauthorized creation, one-time invite consumption, credential separation, token digest storage, expiration, revocation, cross-room isolation, concurrent joins, malformed/forbidden socket messages, control flooding, oversized frames, HTTP body limits, room cleanup, and baseline media/range/playback behavior. Tests run against real local HTTP/WebSocket sockets.

This is a hardened prototype, not a security audit or production certification. Android compilation, device networking, redirected-media behavior and two-hour endurance are unverified. User accounts/MFA, per-user quotas, security audit event collection, malware scanning, automated dependency scanning, end-to-end encryption and DDoS mitigation are not included.

## References used for design

- [OWASP WebSocket Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html): validate authorization and messages and bound resource use. This app uses a larger host message limit to carry one bounded media chunk.
- [Android security best practices](https://developer.android.com/privacy-and-security/security-best-practices): secure network communication and limited data exposure inform the HTTPS-only and transient-credential choices.
