import asyncio
import base64
import unittest
from aiohttp import ClientSession, WSMsgType, web
from server import make_app, byte_range, ROOMS
from security import Policy, SECURITY, digest

CREATOR = "test-only-creator-key-" + "x" * 32
CREATE_HEADERS = {"Authorization": "Bearer " + CREATOR}

class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.app = make_app(Policy(CREATOR, sweep_interval=0.02, creates_per_minute=12))
        self.runner = web.AppRunner(self.app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        self.base = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1])
        self.client = ClientSession()
        self.tasks = []

    async def asyncTearDown(self):
        await self.client.close()
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        await self.runner.cleanup()

    async def room(self, size=600000, mime='video/mp4'):
        async with self.client.post(self.base+'/rooms', headers=CREATE_HEADERS, json={'size':size, 'mime':mime}) as response:
            self.assertEqual(response.status, 201)
            room = await response.json()
        code, token = room['invite'].split('.')
        self.movie = self.base+f'/rooms/{code}/movie'
        self.host_headers = {'Authorization':'Bearer '+room['hostToken']}
        self.invite_headers = {'Authorization':'Bearer '+token}
        host = await self.client.ws_connect(self.base+f'/rooms/{code}/ws', headers=self.host_headers)
        async def serve():
            async for message in host:
                if message.type == WSMsgType.TEXT:
                    data = message.json()
                    if data['type'] == 'read':
                        block = bytes((i % 251 for i in range(data['offset'],data['offset']+data['length'])))
                        await host.send_json({'type':'chunk','id':data['id'],'data':base64.b64encode(block).decode()})
        self.tasks.append(asyncio.create_task(serve()))
        async with self.client.post(self.base+f'/rooms/{code}/join', headers=self.invite_headers) as response:
            self.assertEqual(response.status, 200)
            jdata = await response.json()
            guest_token = jdata['guestToken']
            self.assertEqual(jdata.get('size'), size)
            self.assertEqual(jdata.get('mime'), mime)
        self.guest_headers = {'Authorization': 'Bearer '+guest_token}
        return room, host

    async def test_stream_multichunk_and_ranges(self):
        await self.room()
        for value, start, end, expected in [(None,0,599999,200),('bytes=200-350',200,350,206),('bytes=-42',599958,599999,206),('bytes=599990-',599990,599999,206)]:
            headers = dict(self.guest_headers)
            if value: headers['Range'] = value
            async with self.client.get(self.movie, headers=headers) as response:
                self.assertEqual(response.status, expected)
                self.assertEqual(await response.read(), bytes(i%251 for i in range(start,end+1)))
        async with self.client.head(self.movie, headers=self.guest_headers) as response:
            self.assertEqual(response.content_length,600000)
        async with self.client.get(self.movie, headers={**self.guest_headers,'Range':'bytes=600000-'}) as response:
            self.assertEqual(response.status,416)
        async with self.client.get(self.movie) as response:
            self.assertEqual(response.status,401)
        token_str = self.guest_headers['Authorization'].split()[1]
        async with self.client.get(f"{self.movie}.mkv?token={token_str}") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.content_length, 600000)

    async def test_stream_binary_chunks(self):
        async with self.client.post(self.base+'/rooms', headers=CREATE_HEADERS, json={'size':300000, 'mime':'video/mp4'}) as response:
            self.assertEqual(response.status, 201)
            room = await response.json()
        code, token = room['invite'].split('.')
        movie_url = self.base+f'/rooms/{code}/movie'
        host_headers = {'Authorization':'Bearer '+room['hostToken']}
        invite_headers = {'Authorization':'Bearer '+token}
        host = await self.client.ws_connect(self.base+f'/rooms/{code}/ws', headers=host_headers)
        async def serve_binary():
            async for message in host:
                if message.type == WSMsgType.TEXT:
                    data = message.json()
                    if data['type'] == 'read':
                        block = bytes((i % 251 for i in range(data['offset'], data['offset']+data['length'])))
                        # Binary frame: 24-byte ASCII ID + raw chunk
                        await host.send_bytes(data['id'].encode('ascii') + block)
        self.tasks.append(asyncio.create_task(serve_binary()))
        async with self.client.post(self.base+f'/rooms/{code}/join', headers=invite_headers) as response:
            self.assertEqual(response.status, 200)
            guest_token = (await response.json())['guestToken']
        guest_headers = {'Authorization': 'Bearer '+guest_token}
        async with self.client.get(movie_url, headers={**guest_headers, 'Range': 'bytes=100-299'}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(await response.read(), bytes(i % 251 for i in range(100, 300)))

    async def test_large_file_seek_above_four_gib(self):
        await self.room(9_000_000_000)
        offset = 5_000_000_000
        async with self.client.get(self.movie, headers={**self.guest_headers,'Range':f'bytes={offset}-{offset+123}'}) as response:
            self.assertEqual(response.status,206)
            self.assertEqual(await response.read(),bytes(i%251 for i in range(offset,offset+124)))
            self.assertEqual(response.headers['Content-Range'], f'bytes {offset}-{offset+123}/9000000000')

    async def test_two_people_controls_buffering_and_host_close(self):
        info, host = await self.room()
        guest = await self.client.ws_connect(self.base+f"/rooms/{info['code']}/ws",headers=self.guest_headers)
        await host.send_json({'type':'ready','ready':True})
        await guest.send_json({'type':'ready','ready':True})
        await guest.send_json({'type':'control','position':120000,'playing':True})
        async def until(predicate):
            for _ in range(15):
                data = await guest.receive_json(timeout=2)
                if predicate(data): return data
            self.fail('Expected state was not received')
        data = await until(lambda d:d.get('playing') is True)
        self.assertGreaterEqual(data['position'],120000)
        await guest.send_json({'type':'ready','ready':False})
        await until(lambda d:d.get('playing') is False)
        await guest.send_json({'type':'ready','ready':True})
        await until(lambda d:d.get('playing') is True)
        async with self.client.get(self.base+f"/rooms/{info['code']}/ws",headers=self.guest_headers) as response:
            self.assertEqual(response.status,409)
        async with self.client.delete(self.base+f"/rooms/{info['code']}", headers=self.host_headers) as response:
            self.assertEqual(response.status, 204)
        await until(lambda d:d.get('type')=='error')
        await guest.close()
        async with self.client.get(self.movie,headers=self.guest_headers) as response:
            self.assertEqual(response.status,404)

    async def test_host_disconnect_grace_period_and_reconnect(self):
        info, host = await self.room()
        guest = await self.client.ws_connect(self.base+f"/rooms/{info['code']}/ws", headers=self.guest_headers)
        await host.send_json({'type': 'ready', 'ready': True})
        await guest.send_json({'type': 'ready', 'ready': True})

        async def until(predicate):
            for _ in range(15):
                data = await guest.receive_json(timeout=2)
                if predicate(data): return data
            self.fail('Expected state was not received')

        # Host socket drops (e.g. app switch, screen lock, Wi-Fi handover)
        await host.close()

        # Guest receives state broadcast (playing: False, hostConnected: False) but room is NOT destroyed!
        data = await until(lambda d: d.get('hostConnected') is False)
        self.assertFalse(data.get('playing'))

        # Room is still alive and NOT 404!
        self.assertIn(info['code'], self.app[ROOMS])

        # Host reconnects seamlessly with original hostToken
        host2 = await self.client.ws_connect(self.base+f"/rooms/{info['code']}/ws", headers=self.host_headers)
        self.assertIsNotNone(host2)
        self.assertIsNotNone(self.app[ROOMS][info['code']].host)
        self.assertFalse(self.app[ROOMS][info['code']].host.closed)

        # Guest receives state update indicating host is reconnected!
        data2 = await until(lambda d: d.get('hostConnected') is True)
        self.assertTrue(data2.get('hostConnected'))

        await host2.close()
        await guest.close()

    async def test_input_validation(self):
        for size in [0,-1,True,'100',2**63]:
            async with self.client.post(self.base+'/rooms',headers=CREATE_HEADERS,json={'size':size}) as response:
                self.assertEqual(response.status,400)
        for value in ['bytes=-0','bytes=1-2,4-5','bytes=9-1','junk']:
            with self.assertRaises(ValueError): byte_range(value,100)

    async def test_creator_auth_and_no_browser_origin(self):
        for headers in ({}, {'Authorization':'Bearer wrong'}):
            async with self.client.post(self.base+'/rooms',headers=headers,json={'size':100}) as response:
                self.assertEqual(response.status,401)
                self.assertEqual(response.headers['Cache-Control'],'no-store')
        async with self.client.post(self.base+'/rooms',headers={**CREATE_HEADERS,'Origin':'https://evil.invalid'},json={'size':100}) as response:
            self.assertEqual(response.status,403)
        with self.assertRaises(ValueError): make_app(Policy('short'))

    async def test_reusable_invite_and_token_separation(self):
        room, _ = await self.room()
        async with self.client.post(self.base+f"/rooms/{room['code']}/join",headers=self.invite_headers) as response:
            self.assertEqual(response.status,200)
        for headers in (self.invite_headers, CREATE_HEADERS):
            async with self.client.get(self.movie,headers=headers) as response:
                self.assertEqual(response.status,401)
        stored = self.app[ROOMS][room['code']]
        self.assertEqual(stored.host_token,digest(room['hostToken']))

    async def test_expiry_and_host_revocation(self):
        room, _ = await self.room()
        endpoint = self.base+f"/rooms/{room['code']}"
        async with self.client.delete(endpoint,headers=self.guest_headers) as response:
            self.assertEqual(response.status,403)
        async with self.client.delete(endpoint,headers=self.host_headers) as response:
            self.assertEqual(response.status,204)
        async with self.client.get(self.movie,headers=self.guest_headers) as response:
            self.assertEqual(response.status,404)
        room, _ = await self.room()
        self.app[ROOMS][room['code']].expires = 0
        async with self.client.get(self.movie,headers=self.guest_headers) as response:
            self.assertEqual(response.status,404)
        await asyncio.sleep(0.05)
        self.assertNotIn(room['code'],self.app[ROOMS])

    async def test_invite_expires_before_redemption(self):
        async with self.client.post(self.base+'/rooms',headers=CREATE_HEADERS,json={'size':100}) as response:
            info = await response.json()
        room = self.app[ROOMS][info['code']]
        room.invite_expires = 0
        async with self.client.post(self.base+f"/rooms/{info['code']}/join",headers={'Authorization':'Bearer '+info['invite'].split('.')[1]}) as response:
            self.assertEqual(response.status,401)
        room.expires = 0

    async def test_creation_throttling(self):
        for _ in range(12):
            async with self.client.post(self.base+'/rooms',headers=CREATE_HEADERS,json={}) as response:
                self.assertEqual(response.status,400)
        async with self.client.post(self.base+'/rooms',headers=CREATE_HEADERS,json={}) as response:
            self.assertEqual(response.status,429)
            self.assertEqual(response.headers['Retry-After'],'60')

    async def test_concurrent_guest_handshake_reservation(self):
        room, _ = await self.room()
        endpoint=self.base+f"/rooms/{room['code']}/ws"
        results=await asyncio.gather(*(self.client.ws_connect(endpoint,headers=self.guest_headers) for _ in range(2)),return_exceptions=True)
        sockets=[r for r in results if not isinstance(r,Exception)]
        errors=[r for r in results if isinstance(r,Exception)]
        self.assertEqual(len(sockets),1)
        self.assertEqual(errors[0].status,409)
        await sockets[0].close()

    async def test_reconnect_replaces_stale_connection_without_409(self):
        room, _ = await self.room()
        endpoint = self.base + f"/rooms/{room['code']}/ws"
        ws1 = await self.client.ws_connect(endpoint, headers=self.guest_headers)
        ws2 = await self.client.ws_connect(endpoint + "?reconnect=1", headers=self.guest_headers)
        self.assertFalse(ws2.closed)
        await ws1.close()
        await ws2.close()

    async def test_guest_cannot_impersonate_host_and_bad_messages_close(self):
        room, _ = await self.room()
        endpoint=self.base+f"/rooms/{room['code']}/ws"
        guest=await self.client.ws_connect(endpoint,headers=self.guest_headers)
        await guest.receive_json()
        await guest.send_json({'type':'tick','position':1})
        message=await guest.receive(timeout=2)
        self.assertEqual(message.type,WSMsgType.CLOSE)
        self.assertEqual(message.data,1008)
        await guest.close()
        guest=await self.client.ws_connect(endpoint,headers=self.guest_headers)
        await guest.receive_json()
        await guest.send_str('[]')
        message=await guest.receive(timeout=2)
        self.assertEqual(message.type,WSMsgType.CLOSE)
        self.assertEqual(message.data,1008)
        await guest.close()

    async def test_room_credential_does_not_cross_rooms(self):
        one, _ = await self.room()
        first_movie=self.movie
        await self.room()
        async with self.client.get(first_movie,headers=self.guest_headers) as response:
            self.assertEqual(response.status,401)

    async def test_control_flood_and_oversized_guest_frames(self):
        room, _ = await self.room()
        endpoint=self.base+f"/rooms/{room['code']}/ws"
        guest=await self.client.ws_connect(endpoint,headers=self.guest_headers)
        await guest.receive_json()
        for _ in range(13):
            await guest.send_json({'type':'ready','ready':True})
        while True:
            message=await guest.receive(timeout=2)
            if message.type == WSMsgType.CLOSE:
                self.assertEqual(message.data,1008)
                break
        await guest.close()
        guest=await self.client.ws_connect(endpoint,headers=self.guest_headers)
        await guest.receive_json()
        await guest.send_str('x'*600000)
        message=await guest.receive(timeout=2)
        self.assertEqual(message.type,WSMsgType.CLOSE)
        self.assertEqual(message.data,1009)
        await guest.close()

    async def test_idle_room_cleanup_and_http_body_limit(self):
        async with self.client.post(self.base+'/rooms',headers=CREATE_HEADERS,json={'size':100}) as response:
            info=await response.json()
        self.app[ROOMS][info['code']].created = 0
        await asyncio.sleep(0.05)
        self.assertNotIn(info['code'],self.app[ROOMS])
        async with self.client.post(self.base+'/rooms',headers={**CREATE_HEADERS,'Content-Type':'application/json'},data=' '*5000) as response:
            self.assertEqual(response.status,413)

    async def test_reaction_broadcast(self):
        room, host = await self.room()
        code = room['code']
        guest = await self.client.ws_connect(self.base+f'/rooms/{code}/ws', headers=self.guest_headers)
        await guest.receive_json() # initial state
        await guest.send_json({'type': 'reaction', 'emoji': '🍿'})
        res = await guest.receive_json(timeout=2)
        self.assertEqual(res, {'type': 'reaction', 'emoji': '🍿', 'sender': 'guest'})
        await guest.close()

    async def test_solo_host_playback_and_guest_sync(self):
        # Create room and connect ONLY host
        async with self.client.post(self.base+'/rooms', headers=CREATE_HEADERS, json={'size':600000, 'mime':'video/mp4'}) as response:
            room = await response.json()
        code = room['code']
        host_headers = {'Authorization': 'Bearer ' + room['hostToken']}
        host = await self.client.ws_connect(self.base+f'/rooms/{code}/ws', headers=host_headers)
        state1 = await host.receive_json()
        self.assertFalse(state1['playing'])
        self.assertFalse(state1['guestConnected'])

        # Host sends ready=True and play command
        await host.send_json({'type': 'ready', 'ready': True})
        await host.receive_json() # response from ready

        await host.send_json({'type': 'control', 'position': 5000, 'playing': True})
        state2 = await host.receive_json()
        self.assertTrue(state2['playing'])
        self.assertEqual(state2['position'], 5000)

        # Host pauses
        await host.send_json({'type': 'control', 'position': 8000, 'playing': False})
        state3 = await host.receive_json()
        self.assertFalse(state3['playing'])
        self.assertEqual(state3['position'], 8000)
        await host.close()

    async def test_push_to_talk_voice_relay(self):
        room, host = await self.room()
        code = room['code']
        guest = await self.client.ws_connect(self.base+f'/rooms/{code}/ws', headers=self.guest_headers)
        await guest.receive_json() # initial state
        # Host sends voice note to guest
        await host.send_json({'type': 'voice', 'audio': 'AAAA1111'})
        res = await guest.receive_json(timeout=2)
        self.assertEqual(res, {'type': 'voice', 'audio': 'AAAA1111', 'sender': 'host'})
        await guest.close()

    async def test_lobby_presence_and_in_app_invite(self):
        ws_a = await self.client.ws_connect(self.base + '/lobby/ws')
        await ws_a.send_json({'type': 'hello', 'userId': 'u_a', 'name': 'Parasuram'})
        msg1 = await ws_a.receive_json(timeout=2)
        self.assertEqual(msg1['type'], 'lobby_peers')
        self.assertEqual(len(msg1['peers']), 1)
        self.assertEqual(msg1['peers'][0]['name'], 'Parasuram')

        ws_b = await self.client.ws_connect(self.base + '/lobby/ws')
        await ws_b.send_json({'type': 'hello', 'userId': 'u_b', 'name': 'Alex'})

        # Both receive updated peer list
        async def wait_for_peer(ws, peer_id):
            for _ in range(10):
                msg = await ws.receive_json(timeout=2)
                if msg.get('type') == 'lobby_peers':
                    ids = [p['id'] for p in msg.get('peers', [])]
                    if peer_id in ids:
                        return msg
            self.fail(f'Peer {peer_id} not found in lobby')

        peers_a = await wait_for_peer(ws_a, 'u_b')
        self.assertEqual(len(peers_a['peers']), 2)

        # User B reads its own initial lobby_peers
        initial_b = await ws_b.receive_json(timeout=2)
        self.assertEqual(initial_b['type'], 'lobby_peers')

        # User A sends invite to User B
        await ws_a.send_json({
            'type': 'invite_peer',
            'toUserId': 'u_b',
            'fromName': 'Parasuram',
            'code': 'ROOM123',
            'invite': 'ROOM123.SECRETTOKEN'
        })

        invite_msg = await ws_b.receive_json(timeout=2)
        self.assertEqual(invite_msg['type'], 'incoming_invite')
        self.assertEqual(invite_msg['fromUserId'], 'u_a')
        self.assertEqual(invite_msg['fromName'], 'Parasuram')
        self.assertEqual(invite_msg['code'], 'ROOM123')

        # User B accepts
        await ws_b.send_json({'type': 'invite_response', 'toUserId': 'u_a', 'accepted': True})
        resp_msg = await ws_a.receive_json(timeout=2)
        self.assertEqual(resp_msg['type'], 'invite_response')
        self.assertTrue(resp_msg['accepted'])

        # User B disconnects
        await ws_b.close()
        # User A receives peer list with only User A
        async def wait_for_peer_gone(ws, peer_id):
            for _ in range(10):
                msg = await ws.receive_json(timeout=2)
                if msg.get('type') == 'lobby_peers':
                    ids = [p['id'] for p in msg.get('peers', [])]
                    if peer_id not in ids:
                        return msg
            self.fail(f'Peer {peer_id} still found in lobby')

        left_peers = await wait_for_peer_gone(ws_a, 'u_b')
        self.assertEqual(len(left_peers['peers']), 1)

        await ws_a.close()

if __name__ == '__main__':
    unittest.main()
