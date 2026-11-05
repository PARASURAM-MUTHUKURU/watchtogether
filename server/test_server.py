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

    async def room(self, size=600000):
        async with self.client.post(self.base+'/rooms', headers=CREATE_HEADERS, json={'size':size, 'mime':'video/mp4'}) as response:
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
            guest_token = (await response.json())['guestToken']
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
        await host.close()
        await until(lambda d:d.get('type')=='error')
        await guest.close()
        async with self.client.get(self.movie,headers=self.guest_headers) as response:
            self.assertEqual(response.status,404)

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

if __name__ == '__main__':
    unittest.main()
