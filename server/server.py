"""Two-person, bounded-memory movie relay. Put behind HTTPS/WSS in production."""
import asyncio
import base64
import json
import re
import secrets
import time
from dataclasses import dataclass, field
from aiohttp import web, WSMsgType
from security import Policy, SECURITY, LIMITS, Bucket, digest, matches, bearer, guard, headers

ROOMS = web.AppKey('rooms', dict)
CHUNK = 256 * 1024
MAX_ROOMS = 100

@dataclass
class Room:
    code: str
    host_token: str
    invite_hash: str
    expires: float
    invite_expires: float
    size: int
    mime: str
    guest_token: str = ""
    revoked: bool = False
    reserved: set = field(default_factory=set)
    host: object = None
    guest: object = None
    pending: dict = field(default_factory=dict)
    position: int = 0
    playing: bool = False
    desired: bool = False
    ready: dict = field(default_factory=lambda: dict(host=False, guest=False))
    updated: float = field(default_factory=time.monotonic)
    created: float = field(default_factory=time.monotonic)
    stream_slots: object = field(default_factory=lambda: asyncio.Semaphore(4))

    def state(self):
        now = time.monotonic()
        pos = self.position + (int((now - self.updated)*1000) if self.playing else 0)
        return dict(type='state', position=pos, playing=self.playing)

async def broadcast(room):
    effective = room.desired and all(room.ready.values()) and room.host is not None and room.guest is not None
    if effective != room.playing:
        room.position = room.state()['position']
        room.updated = time.monotonic()
        room.playing = effective
    payload = room.state()
    for ws in (room.host, room.guest):
        if ws is not None and not ws.closed:
            try:
                await ws.send_json(payload)
            except (ConnectionError, RuntimeError):
                # A dead guest transport must not unwind the host's socket loop.
                pass

def live(request):
    room = request.app[ROOMS].get(request.match_info['code'])
    if room is None or room.revoked or time.monotonic() >= room.expires:
        raise web.HTTPNotFound(text='Room expired or closed')
    return room

def resolve(request):
    room = live(request)
    token = bearer(request)
    if matches(token, room.host_token):
        return room, 'host'
    if matches(token, room.guest_token):
        return room, 'guest'
    raise web.HTTPUnauthorized(text='Invalid room credential')

async def create(request):
    policy = request.app[SECURITY]
    if not matches(bearer(request), digest(policy.creator_key)):
        raise web.HTTPUnauthorized(text='Invalid creator credential')
    rooms = request.app[ROOMS]
    if len(rooms) >= MAX_ROOMS:
        raise web.HTTPServiceUnavailable(text='Room capacity reached')
    try:
        data = await request.json()
        size = data['size']
        if type(size) is not int or size <= 0 or size > 2**63-1:
            raise ValueError()
        mime = data.get('mime', 'video/mp4')
        if not isinstance(mime, str) or not re.fullmatch(r'video/[a-zA-Z0-9.+-]+', mime):
            raise ValueError()
    except (ValueError, KeyError, TypeError):
        raise web.HTTPBadRequest(text='Positive 64-bit size and video MIME required')
    code, host_token, invitation = secrets.token_urlsafe(12), secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    now = time.monotonic()
    room = Room(code=code, host_token=digest(host_token), invite_hash=digest(invitation),
                expires=now+policy.room_ttl, invite_expires=now+policy.room_ttl, size=size, mime=mime)
    rooms[code] = room
    return web.json_response(dict(code=code, hostToken=host_token,
                                  invite=code+'.'+invitation, inviteExpiresIn=policy.room_ttl), status=201)

async def join(request):
    room = live(request)
    invitation = bearer(request)
    if not matches(invitation, room.invite_hash) or time.monotonic() >= room.invite_expires:
        raise web.HTTPUnauthorized(text='Invite invalid, already used or expired')
    if room.host is None:
        raise web.HTTPConflict(text='Wait for the host to connect')
    # If a previous guest session is still open or reserved, clean it up so reconnection is instant
    room.reserved.discard('guest')
    if room.guest is not None and not room.guest.closed:
        try:
            await room.guest.close(code=1000, message=b'Replaced by new session')
        except Exception:
            pass
    room.guest = None
    guest_token = secrets.token_urlsafe(32)
    room.guest_token = digest(guest_token)
    return web.json_response(dict(guestToken=guest_token))

async def close_room(app, room):
    if room.revoked:
        return
    room.revoked = True
    app[ROOMS].pop(room.code, None)
    for future in list(room.pending.values()):
        if not future.done():
            future.set_exception(IOError('Room closed'))
    for ws in (room.host, room.guest):
        if ws is not None and not ws.closed:
            try:
                await ws.send_json(dict(type='error', message='Room closed or expired'))
                await ws.close(code=1001, message=b'Room closed')
            except (ConnectionError, RuntimeError):
                pass

async def revoke(request):
    room, role = resolve(request)
    if role != 'host':
        raise web.HTTPForbidden(text='Only host can close the room')
    await close_room(request.app, room)
    return web.Response(status=204)

async def sweep(app):
    policy = app[SECURITY]
    while True:
        try:
            await asyncio.sleep(policy.sweep_interval)
            now = time.monotonic()
            for room in list(app[ROOMS].values()):
                if now >= room.expires or (room.host is None and now-room.created >= policy.unattached_ttl):
                    await close_room(app, room)
        except asyncio.CancelledError:
            break
        except Exception:
            pass

async def lifecycle(app):
    task = asyncio.create_task(sweep(app))
    yield
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    for room in list(app[ROOMS].values()):
        await close_room(app, room)

async def socket(request):
    room, role = resolve(request)
    if role in room.reserved:
        raise web.HTTPConflict(text='This room role is already connected')
    # Reserve before the handshake await; two simultaneous sockets cannot take a role.
    room.reserved.add(role)
    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=CHUNK*2, compress=False)
    policy = request.app[SECURITY]
    messages, controls = Bucket(policy.ws_per_second, 1), Bucket(policy.controls_per_second, 1)
    try:
        await ws.prepare(request)
        if room.revoked:
            await ws.close(code=1008, message=b'Room closed')
            return ws
        setattr(room, role, ws)
        await broadcast(room)
        async for msg in ws:
            if room.revoked or time.monotonic() >= room.expires:
                break
            if msg.type != WSMsgType.TEXT:
                if msg.type == WSMsgType.BINARY:
                    if role == 'host' and len(msg.data) >= 24:
                        raw = msg.data
                        ident = raw[:24].decode('ascii', errors='ignore')
                        future = room.pending.get(ident)
                        if future and not future.done():
                            block = raw[24:]
                            if len(block) > CHUNK:
                                raise ValueError()
                            future.set_result(block)
                            continue
                    await ws.close(code=1008, message=b'Text JSON required')
                continue
            if not messages.allow():
                await ws.close(code=1008, message=b'Message rate exceeded')
                break
            try:
                data = json.loads(msg.data)
                if not isinstance(data, dict):
                    raise ValueError()
                kind = data.get('type')
                if kind == 'chunk' and role == 'host':
                    ident = data.get('id')
                    if not isinstance(ident, str):
                        raise ValueError()
                    future = room.pending.get(ident)
                    if future and not future.done():
                        if data.get('error') is True:
                            future.set_exception(IOError('Host could not read movie'))
                        else:
                            block = base64.b64decode(data['data'], validate=True)
                            if len(block) > CHUNK:
                                raise ValueError()
                            future.set_result(block)
                elif kind in ('control', 'tick', 'ready'):
                    if not controls.allow():
                        raise ValueError()
                    if kind == 'ready':
                        if type(data.get('ready')) is not bool:
                            raise ValueError()
                        room.ready[role] = data['ready']
                    else:
                        pos = data['position']
                        if type(pos) is not int or not 0 <= pos <= 2**53-1:
                            raise ValueError()
                        if kind == 'tick' and role != 'host':
                            raise ValueError()
                        if kind == 'control':
                            if type(data.get('playing')) is not bool:
                                raise ValueError()
                            room.desired = data['playing']
                        room.position, room.updated = pos, time.monotonic()
                    await broadcast(room)
                elif kind == 'reaction':
                    if not controls.allow():
                        raise ValueError()
                    emoji = data.get('emoji')
                    if not isinstance(emoji, str) or not (0 < len(emoji) <= 16):
                        raise ValueError()
                    msg = {'type': 'reaction', 'emoji': emoji, 'sender': role}
                    for peer in (room.host, room.guest):
                        if peer and not peer.closed:
                            try:
                                await peer.send_json(msg)
                            except Exception:
                                pass
                elif kind == 'voice':
                    if not controls.allow():
                        raise ValueError()
                    audio = data.get('audio')
                    if not isinstance(audio, str) or not (0 < len(audio) <= 350_000):
                        raise ValueError()
                    peer = room.guest if role == 'host' else room.host
                    if peer and not peer.closed:
                        try:
                            await peer.send_json({'type': 'voice', 'audio': audio, 'sender': role})
                        except Exception:
                            pass
                else:
                    raise ValueError()
            except (ValueError, KeyError, TypeError):
                await ws.close(code=1008, message=b'Invalid or forbidden message')
                break
    finally:
        room.reserved.discard(role)
        if getattr(room, role) is ws:
            setattr(room, role, None)
            room.ready[role] = False
            room.position = room.state()['position']
            room.playing = False
            room.updated = time.monotonic()
            if role == 'host':
                await close_room(request.app, room)
            elif not room.revoked:
                await broadcast(room)
    return ws

def byte_range(value, size):
    if not value:
        return 0, size-1, False
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', value)
    if not match or not any(match.groups()):
        raise ValueError()
    a, b = match.groups()
    if not a:
        if int(b) == 0:
            raise ValueError()
        start, end = max(0, size-int(b)), size-1
    else:
        start, end = int(a), min(int(b), size-1) if b else size-1
    if start >= size or start > end:
        raise ValueError()
    return start, end, True

async def fetch_block(room, offset, length):
    ident = secrets.token_hex(12)
    future = asyncio.get_running_loop().create_future()
    room.pending[ident] = future
    try:
        if room.revoked or time.monotonic() >= room.expires or room.host is None or room.host.closed:
            raise IOError('Host offline')
        await room.host.send_json(dict(type='read', id=ident, offset=offset, length=length))
        block = await asyncio.wait_for(future, 30)
        if len(block) != length:
            raise IOError('Short movie read')
        return block
    finally:
        room.pending.pop(ident, None)

async def movie(request):
    room, _ = resolve(request)
    if room.host is None:
        raise web.HTTPServiceUnavailable(text='Host is offline')
    try:
        start, end, partial = byte_range(request.headers.get('Range'), room.size)
    except ValueError:
        raise web.HTTPRequestRangeNotSatisfiable(headers={'Content-Range':f'bytes */{room.size}'})
    headers = {'Content-Type':room.mime, 'Accept-Ranges':'bytes',
               'Content-Length':str(end-start+1), 'Cache-Control':'no-store'}
    if partial:
        headers['Content-Range'] = f'bytes {start}-{end}/{room.size}'
    response = web.StreamResponse(status=206 if partial else 200, headers=headers)
    if request.method == 'HEAD':
        await response.prepare(request)
        return response
    if room.stream_slots.locked():
        raise web.HTTPTooManyRequests(text='Too many video connections')
    async with room.stream_slots:
        next_task = None
        try:
            # Prefetch initial block before headers so initial host failures get an HTTP error.
            chunk_len = min(CHUNK, end - start + 1)
            next_task = asyncio.create_task(fetch_block(room, start, chunk_len))
            block = await next_task
            start += len(block)
            await response.prepare(request)
            while True:
                if start <= end:
                    next_len = min(CHUNK, end - start + 1)
                    next_task = asyncio.create_task(fetch_block(room, start, next_len))
                else:
                    next_task = None

                await response.write(block)
                if next_task is None:
                    break
                block = await next_task
                start += len(block)
            await response.write_eof()
        except (IOError, asyncio.TimeoutError, ConnectionError):
            if next_task and not next_task.done():
                next_task.cancel()
            if not response.prepared:
                raise web.HTTPServiceUnavailable(text='Movie source unavailable')
            if request.transport:
                request.transport.close()
    return response

async def health(request):
    return web.json_response({'ok': True})

def make_app(policy=None):
    app = web.Application(client_max_size=4096, middlewares=[guard])
    app[SECURITY] = policy or Policy.from_env()
    app[LIMITS] = {}
    app.on_response_prepare.append(headers)
    app.cleanup_ctx.append(lifecycle)
    app[ROOMS] = {}
    app.router.add_post('/rooms', create)
    app.router.add_post('/rooms/{code}/join', join)
    app.router.add_delete('/rooms/{code}', revoke)
    app.router.add_get('/rooms/{code}/ws', socket)
    app.router.add_get('/rooms/{code}/movie', movie)
    app.router.add_get('/health', health)
    return app

if __name__ == '__main__':
    host = __import__('os').environ.get('BIND_HOST', '127.0.0.1')
    port = int(__import__('os').environ.get('PORT', '8080'))
    web.run_app(make_app(), host=host, port=port, access_log=None)
