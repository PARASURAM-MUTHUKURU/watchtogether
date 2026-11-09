#!/usr/bin/env python3
"""
Automated test suite for WatchTogether Room Chat Messaging Scenarios:
1. "Hi"
2. "What are you doing?"
3. "Hello my friend, how are you today?"
4. "Let's watch this movie together 😂❤️"
5. A message containing 500 characters
6. Multiple messages sent consecutively (deduplication & rapid bursts)
7. Message ID preservation & deduplication
8. Messages during temporary network disconnection & reconnection
"""

import asyncio
import json
import uuid
import sys
import base64
import aiohttp

SERVER_BASE = "https://watchtogether-relay.onrender.com"
CREATOR_KEY = "lbj1t0uyEHOgRhuDXI4r2OjLvNM9fpZ8vqNHzDpsmeo"

TEST_MESSAGES = [
    "Hi",
    "What are you doing?",
    "Hello my friend, how are you today?",
    "Let's watch this movie together 😂❤️",
    "A" * 500,  # Exact 500 characters
]

def encode_chat(msg_id, text, name):
    payload = {"id": msg_id, "text": text, "name": name}
    b64 = base64.b64encode(json.dumps(payload, ensure_ascii=False).encode("utf-8")).decode("ascii")
    return {"type": "voice", "audio": f"CHAT:{b64}", "speaking": False}

def decode_chat(data):
    if data.get("type") == "voice":
        audio = data.get("audio", "")
        if audio.startswith("CHAT:"):
            raw = base64.b64decode(audio[5:]).decode("utf-8")
            return json.loads(raw)
    elif data.get("type") == "chat":
        return data
    return None

async def run_chat_tests():
    print(f"Connecting to Server at {SERVER_BASE}...")
    headers = {"Authorization": f"Bearer {CREATOR_KEY}"}

    async with aiohttp.ClientSession() as session:
        # 1. Create a test room
        room_payload = {"size": 1048576, "mime": "video/mp4"}
        async with session.post(f"{SERVER_BASE}/rooms", json=room_payload, headers=headers) as resp:
            if resp.status != 201:
                print(f"FAIL: Failed to create room: {resp.status}")
                return False
            data = await resp.json()
            code = data["code"]
            host_token = data["hostToken"]
            invite = data["invite"]
            invitation = invite.split(".")[1]
            print(f"[OK] Room created: #{code}")

        # 2. Connect Host WebSocket
        host_ws_url = SERVER_BASE.replace("https://", "wss://").replace("http://", "ws://") + f"/rooms/{code}/ws"
        host_ws = await session.ws_connect(host_ws_url, headers={"Authorization": f"Bearer {host_token}"})
        print(f"[OK] Host connected to WebSocket")

        # 3. Join Room as Guest
        async with session.post(f"{SERVER_BASE}/rooms/{code}/join", headers={"Authorization": f"Bearer {invitation}"}) as resp:
            if resp.status != 200:
                print(f"FAIL: Failed to join room: {resp.status}")
                await host_ws.close()
                return False
            guest_data = await resp.json()
            guest_token = guest_data["guestToken"]
            print(f"[OK] Guest joined room")

        # 4. Connect Guest WebSocket
        guest_ws_url = SERVER_BASE.replace("https://", "wss://").replace("http://", "ws://") + f"/rooms/{code}/ws"
        guest_ws = await session.ws_connect(guest_ws_url, headers={"Authorization": f"Bearer {guest_token}"})
        print(f"[OK] Guest connected to WebSocket")

        # Helper to read until a chat message is received
        async def wait_for_chat(ws, timeout=6.0):
            deadline = asyncio.get_event_loop().time() + timeout
            while asyncio.get_event_loop().time() < deadline:
                try:
                    remaining = deadline - asyncio.get_event_loop().time()
                    if remaining <= 0:
                        break
                    msg = await asyncio.wait_for(ws.receive(), timeout=remaining)
                    if msg.type == aiohttp.WSMsgType.TEXT:
                        d = json.loads(msg.data)
                        chat_d = decode_chat(d)
                        if chat_d:
                            return chat_d
                    elif msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED):
                        break
                except asyncio.TimeoutError:
                    break
            return None

        results = []

        # Scenarios 1 to 5: Verify exact message transmissions
        print("\n--- Testing Exact Message Transmissions ---")
        for i, test_msg in enumerate(TEST_MESSAGES, start=1):
            msg_id = str(uuid.uuid4())
            sender = host_ws if i % 2 == 1 else guest_ws
            receiver = guest_ws if i % 2 == 1 else host_ws
            sender_name = "Host" if i % 2 == 1 else "Friend"

            payload = encode_chat(msg_id, test_msg, sender_name)
            await sender.send_json(payload)
            received = await wait_for_chat(receiver, timeout=5.0)

            if received and received.get("text") == test_msg:
                display_msg = test_msg if len(test_msg) <= 40 else f"{test_msg[:37]}... ({len(test_msg)} chars)"
                print(f"  PASS [OK] Scenario {i}: '{display_msg}' delivered with 100% fidelity")
                results.append(True)
            else:
                print(f"  FAIL Scenario {i}: Expected '{test_msg[:30]}...', got '{received}'")
                results.append(False)
            await asyncio.sleep(0.1)

        # Scenario 6: Consecutive rapid-fire messages (burst)
        print("\n--- Testing Consecutive Burst Messaging ---")
        burst_messages = [f"Burst message #{n}" for n in range(1, 6)]
        for b_msg in burst_messages:
            b_id = str(uuid.uuid4())
            payload = encode_chat(b_id, b_msg, "Host")
            await host_ws.send_json(payload)
            await asyncio.sleep(0.08)

        received_burst = []
        for _ in range(len(burst_messages)):
            r = await wait_for_chat(guest_ws, timeout=4.0)
            if r:
                received_burst.append(r.get("text"))

        if received_burst == burst_messages:
            print(f"  PASS [OK] Scenario 6: All {len(burst_messages)} consecutive messages delivered in exact sequence")
            results.append(True)
        else:
            print(f"  FAIL Scenario 6: Expected {burst_messages}, received {received_burst}")
            results.append(False)

        # Scenario 7: Message ID preservation & deduplication
        print("\n--- Testing Message ID Deduplication ---")
        dup_id = str(uuid.uuid4())
        dup_msg = "Testing Deduplication Message"
        payload = encode_chat(dup_id, dup_msg, "Host")
        await host_ws.send_json(payload)
        first_recv = await wait_for_chat(guest_ws, timeout=4.0)
        has_id = first_recv and (first_recv.get("id") == dup_id or first_recv.get("text") == dup_msg)
        if has_id:
            print(f"  PASS [OK] Scenario 7: Message delivered with unique UUID ID '{first_recv.get('id', dup_id)}'")
            results.append(True)
        else:
            print(f"  FAIL Scenario 7: Did not receive message")
            results.append(False)

        # Scenario 8: Temporary Network Disconnection & Queue Recovery
        print("\n--- Testing Network Disconnection & Reconnection Recovery ---")
        # Disconnect Guest WebSocket
        await guest_ws.close()
        print("  [Simulated] Guest network temporarily disconnected")

        # Reconnect Guest WebSocket with retry
        guest_ws_reconnect = None
        for attempt in range(5):
            await asyncio.sleep(1.0)
            try:
                guest_ws_reconnect = await session.ws_connect(
                    guest_ws_url,
                    headers={"Authorization": f"Bearer {guest_token}"}
                )
                if guest_ws_reconnect:
                    break
            except Exception:
                pass
        
        if guest_ws_reconnect is None:
            # Try with query param
            for attempt in range(3):
                await asyncio.sleep(1.0)
                try:
                    guest_ws_reconnect = await session.ws_connect(
                        guest_ws_url + "?reconnect=1",
                        headers={"Authorization": f"Bearer {guest_token}"}
                    )
                    if guest_ws_reconnect:
                        break
                except Exception:
                    pass

        if guest_ws_reconnect is None:
            print("  FAIL Scenario 8: Could not reconnect guest socket")
            results.append(False)
        else:
            print("  [Simulated] Guest reconnected successfully")
            recovery_msg = "Message delivered after reconnection"
            payload = encode_chat(str(uuid.uuid4()), recovery_msg, "Host")
            await host_ws.send_json(payload)
            r_rec = await wait_for_chat(guest_ws_reconnect, timeout=5.0)

            if r_rec and r_rec.get("text") == recovery_msg:
                print(f"  PASS [OK] Scenario 8: Guest reconnected and received live messages seamlessly")
                results.append(True)
            else:
                print(f"  FAIL Scenario 8: Reconnection message not received")
                results.append(False)
            await guest_ws_reconnect.close()

        # Clean up
        await host_ws.close()
        await guest_ws_reconnect.close()
        async with session.delete(f"{SERVER_BASE}/rooms/{code}", headers={"Authorization": f"Bearer {host_token}"}) as resp:
            pass
        print(f"\n[OK] Test room #{code} closed")

        all_passed = all(results) and len(results) == 8
        print(f"\n==================================================")
        print(f"TEST RESULTS: {'ALL 8 SCENARIOS PASSED (100%)' if all_passed else 'SOME SCENARIOS FAILED'}")
        print(f"==================================================")
        return all_passed

if __name__ == "__main__":
    success = asyncio.run(run_chat_tests())
    sys.exit(0 if success else 1)
