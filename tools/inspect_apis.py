import asyncio
import aiohttp
import json
import time

SERVER = "https://watchtogether-relay.onrender.com"
CREATOR_KEY = "lbj1t0uyEHOgRhuDXI4r2OjLvNM9fpZ8vqNHzDpsmeo"

async def test_apis():
    results = []
    print(f"Connecting to Relay Server: {SERVER}\n" + "="*50)
    
    async with aiohttp.ClientSession() as session:
        # 1. Health check
        t0 = time.time()
        try:
            async with session.get(f"{SERVER}/health") as resp:
                latency = int((time.time() - t0) * 1000)
                data = await resp.json()
                ok = resp.status == 200 and data.get("ok") is True
                results.append(("GET /health", resp.status, f"OK ({latency}ms)", ok))
        except Exception as e:
            results.append(("GET /health", "ERR", str(e), False))

        # 2. Auth Rejection check (invalid creator key)
        try:
            bad_headers = {"Authorization": "Bearer invalid_key_12345"}
            async with session.post(f"{SERVER}/rooms", headers=bad_headers, json={"size": 1000, "mime": "video/mp4"}) as resp:
                ok = resp.status == 401
                results.append(("POST /rooms (Invalid Key)", resp.status, "Rejected 401 Unauthorized as expected", ok))
        except Exception as e:
            results.append(("POST /rooms (Invalid Key)", "ERR", str(e), False))

        # 3. Create Room (valid creator key)
        room_code = None
        host_token = None
        invite_str = None
        try:
            headers = {"Authorization": f"Bearer {CREATOR_KEY}"}
            payload = {"size": 104857600, "mime": "video/mp4"}
            async with session.post(f"{SERVER}/rooms", headers=headers, json=payload) as resp:
                data = await resp.json()
                room_code = data.get("code")
                host_token = data.get("hostToken")
                invite_str = data.get("invite")
                ok = resp.status == 201 and bool(room_code and host_token and invite_str)
                results.append(("POST /rooms (Create Room)", resp.status, f"Room: #{room_code}", ok))
        except Exception as e:
            results.append(("POST /rooms (Create Room)", "ERR", str(e), False))

        # 4. Host WebSocket connection & state broadcast
        if room_code and host_token:
            ws_url = SERVER.replace("https://", "wss://").replace("http://", "ws://") + f"/rooms/{room_code}/ws"
            try:
                ws_headers = {"Authorization": f"Bearer {host_token}"}
                async with session.ws_connect(ws_url, headers=ws_headers, timeout=10) as ws:
                    msg = await ws.receive_json(timeout=5)
                    ok = msg.get("type") == "state" and msg.get("hostConnected") is True
                    results.append(("WS /rooms/{code}/ws (Host Connect)", "101", f"Received initial state: playing={msg.get('playing')}, hostConnected={msg.get('hostConnected')}", ok))
                    
                    # 5. Join Room while host is connected
                    parts = invite_str.split('.')
                    join_token = parts[1]
                    join_headers = {"Authorization": f"Bearer {join_token}"}
                    async with session.post(f"{SERVER}/rooms/{room_code}/join", headers=join_headers) as join_resp:
                        join_data = await join_resp.json()
                        guest_token = join_data.get("guestToken")
                        ok_join = join_resp.status == 200 and bool(guest_token)
                        results.append(("POST /rooms/{code}/join (Guest Join)", join_resp.status, f"Guest Token acquired", ok_join))
            except Exception as e:
                results.append(("WS /rooms/{code}/ws", "ERR", str(e), False))

        # 6. Revoke / Delete Room
        if room_code and host_token:
            try:
                del_headers = {"Authorization": f"Bearer {host_token}"}
                async with session.delete(f"{SERVER}/rooms/{room_code}", headers=del_headers) as del_resp:
                    ok = del_resp.status == 204
                    results.append(("DELETE /rooms/{code} (Revoke Room)", del_resp.status, "Room closed successfully", ok))
            except Exception as e:
                results.append(("DELETE /rooms/{code}", "ERR", str(e), False))

        # 7. Lobby WebSocket Presence test
        lobby_ws_url = SERVER.replace("https://", "wss://").replace("http://", "ws://") + "/lobby/ws"
        try:
            async with session.ws_connect(lobby_ws_url, timeout=10) as ws:
                hello = {"type": "hello", "userId": "test_user_inspector", "name": "Inspector"}
                await ws.send_json(hello)
                lobby_msg = await ws.receive_json(timeout=5)
                ok = lobby_msg.get("type") == "lobby_peers"
                peers = lobby_msg.get("peers", [])
                results.append(("WS /lobby/ws (Lobby Presence)", "101", f"Handshake verified ({len(peers)} peers online)", ok))
        except Exception as e:
            results.append(("WS /lobby/ws", "ERR", str(e), False))

    print("\nAPI INSPECTION SUMMARY:")
    all_ok = True
    for name, status, details, ok in results:
        status_icon = "PASS [OK]" if ok else "FAIL [X]"
        if not ok: all_ok = False
        print(f"  {status_icon:<10} | {name:<35} | Status: {str(status):<5} | {details}")
    
    print("="*50)
    print(f"OVERALL STATUS: {'ALL APIS OPERATIONAL' if all_ok else 'SOME ISSUES DETECTED'}")

if __name__ == "__main__":
    asyncio.run(test_apis())
