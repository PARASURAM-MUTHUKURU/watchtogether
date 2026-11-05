"""Simulate a friend joining your WatchTogether room from this PC.
Tests real-time WebSocket sync (play/pause/seek) and chunk streaming directly from your phone.
"""
import asyncio
import json
import re
import sys
import time
import aiohttp

async def run_friend(invite_str: str):
    invite_str = invite_str.strip()
    if '|' not in invite_str:
        print("❌ Error: Invite must be in format: server|code.token")
        return

    origin, code_token = invite_str.split('|', 1)
    origin = origin.strip().rstrip('/')
    code_token = code_token.strip()

    if '.' not in code_token:
        print("❌ Error: Invalid code.token portion in invite")
        return

    code, invite_token = code_token.split('.', 1)
    code = code.strip()
    invite_token = invite_token.strip()

    print(f"🔗 Target Server: {origin}")
    print(f"🔑 Room Code:    #{code}")
    print(f"🎟  Invite Token: {invite_token[:8]}...")
    print("-" * 55)

    headers = {"Authorization": f"Bearer {invite_token}"}
    async with aiohttp.ClientSession() as session:
        # Step 1: Redeem invite
        print("⏳ Step 1/3: Joining room via relay...")
        join_url = f"{origin}/rooms/{code}/join"
        try:
            async with session.post(join_url, headers=headers) as resp:
                if resp.status == 409:
                    print("⚠️  Host is still connecting on phone. Retrying in 2 seconds...")
                    await asyncio.sleep(2)
                    async with session.post(join_url, headers=headers) as resp2:
                        data = await resp2.json()
                        resp = resp2
                else:
                    data = await resp.json()

                if resp.status != 200:
                    print(f"❌ Join failed with HTTP {resp.status}: {data}")
                    return
                guest_token = data.get("guestToken")
                print(f"✅ Step 1 complete: Received friend token ({guest_token[:8]}...)")
        except Exception as e:
            print(f"❌ Failed to reach relay server: {e}")
            return

        # Step 2: Test chunk streaming from host phone
        print("\n⏳ Step 2/3: Testing video chunk streaming from your phone...")
        movie_url = f"{origin}/rooms/{code}/movie"
        chunk_headers = {
            "Authorization": f"Bearer {guest_token}",
            "Range": "bytes=0-262143"  # Read 1st 256KB chunk
        }
        t0 = time.monotonic()
        try:
            async with session.get(movie_url, headers=chunk_headers) as resp:
                if resp.status in (200, 206):
                    chunk_bytes = await resp.read()
                    elapsed = time.monotonic() - t0
                    speed_mbps = (len(chunk_bytes) / 1024 / 1024) / max(0.001, elapsed)
                    print(f"✅ Step 2 complete: Successfully fetched {len(chunk_bytes)} bytes from your phone!")
                    print(f"   ⏱ Latency: {int(elapsed * 1000)}ms · Speed: {speed_mbps:.2f} MB/s")
                else:
                    print(f"⚠️ Chunk test returned HTTP {resp.status} (Host might still be preparing stream)")
        except Exception as e:
            print(f"⚠️ Video stream test warning: {e}")

        # Step 3: Establish WebSocket sync
        print("\n⏳ Step 3/3: Connecting to real-time sync WebSocket...")
        ws_url = origin.replace("https://", "wss://").replace("http://", "ws://") + f"/rooms/{code}/ws"
        ws_headers = {"Authorization": f"Bearer {guest_token}"}

        try:
            async with session.ws_connect(ws_url, headers=ws_headers) as ws:
                print("🎉 Connected as Friend! Listening to your phone's controls in real-time.\n")
                print("👉 Try touching controls on your phone:")
                print("   • Press PLAY on phone   -> You'll see [PLAY] notification here")
                print("   • Press PAUSE on phone  -> You'll see [PAUSE] notification here")
                print("   • Seek +30s on phone    -> You'll see [SEEK] notification here\n")
                print("-" * 55)

                # Send ready flag
                await ws.send_json({"type": "ready", "ready": True})

                async for msg in ws:
                    if msg.type == aiohttp.WSMsgType.TEXT:
                        data = json.loads(msg.data)
                        msg_type = data.get("type")
                        if msg_type == "state":
                            pos_sec = data.get("position", 0) / 1000.0
                            is_playing = data.get("playing", False)
                            status_icon = "▶️ PLAYING" if is_playing else "⏸ PAUSED"
                            mins = int(pos_sec // 60)
                            secs = int(pos_sec % 60)
                            print(f"[PHONE EVENT] {status_icon} at {mins:02d}:{secs:02d} (raw: {data.get('position')}ms)")
                        elif msg_type == "error":
                            print(f"⚠️ Server error message: {data}")
                    elif msg.type == aiohttp.WSMsgType.CLOSED:
                        print("🔴 Room disconnected (host closed room or left)")
                        break
                    elif msg.type == aiohttp.WSMsgType.ERROR:
                        print(f"🔴 WebSocket error: {ws.exception()}")
                        break
        except Exception as e:
            print(f"❌ WebSocket connection failed: {e}")

if __name__ == '__main__':
    if len(sys.argv) > 1:
        invite = sys.argv[1]
    else:
        print("=" * 55)
        print("🎬 WatchTogether - Virtual Friend Tester")
        print("=" * 55)
        invite = input("Paste the invite link from your phone: ").strip()

    if invite:
        asyncio.run(run_friend(invite))

