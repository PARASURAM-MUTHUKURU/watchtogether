# Validation record — v0.2

Executed 5 November 2026 (India time).

- Python 3.12 runtime, aiohttp 3.14.4.
- `python -m unittest discover -s server -v`: 14 tests passed.
- Tests use real localhost HTTP/WebSocket connections and simulated file readers.
- Python source compilation passed.
- ZIP integrity checked before delivery.

Security cases: unauthorized creator access, rejected browser Origin, invalid startup key, invite replay, invitation/session separation, digest storage, guest revocation denial, host revocation, immediate room authorization expiry and scheduled cleanup, invite expiry, create throttling, concurrent guest joins, guest host-impersonation denial, malformed JSON message shapes, cross-room tokens, control flooding, oversized frames and HTTP body limits.

Media cases: multichunk byte equality, range/suffix/open ranges, HEAD metadata, invalid ranges, simulated 9 GB source seeking beyond 4 GiB, two-person controls, buffering coordination and host-disconnect cleanup.

Not run: Android Gradle build, APK installation, physical-device tests, live HTTPS proxy configuration, redirected-media device verification, load/throughput/battery testing, two-hour endurance, dependency vulnerability scan or third-party penetration test. Passing this suite does not establish production security.
