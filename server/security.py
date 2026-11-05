"""Authentication and bounded resource policy for a private two-person relay."""
import asyncio
import hashlib
import os
import secrets
import time
from dataclasses import dataclass
from aiohttp import web

SECURITY = web.AppKey('security', object)
LIMITS = web.AppKey('limits', dict)

@dataclass(frozen=True)
class Policy:
    creator_key: str
    room_ttl: float = 12 * 3600
    invite_ttl: float = 600
    unattached_ttl: float = 600
    sweep_interval: float = 10
    http_per_minute: int = 180
    creates_per_minute: int = 6
    ws_per_second: int = 80
    controls_per_second: int = 12

    def __post_init__(self):
        if len(self.creator_key) < 32 or not self.creator_key.isascii():
            raise ValueError('Set WATCHTOGETHER_CREATOR_KEY to a random ASCII secret of at least 32 characters')
        if min(self.room_ttl, self.invite_ttl, self.unattached_ttl, self.sweep_interval,
               self.http_per_minute, self.creates_per_minute, self.ws_per_second, self.controls_per_second) <= 0:
            raise ValueError('Policy limits must be positive')

    @classmethod
    def from_env(cls):
        return cls(os.environ.get('WATCHTOGETHER_CREATOR_KEY', ''))


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def matches(value, expected):
    return bool(expected) and secrets.compare_digest(digest(value), expected)


def bearer(request):
    header = request.headers.get('Authorization', '')
    if not header.startswith('Bearer ') or len(header) > 512:
        raise web.HTTPUnauthorized(text='Bearer credential required')
    return header[7:]


class Bucket:
    def __init__(self, limit, period):
        self.limit, self.period = limit, period
        self.used, self.start = 0, time.monotonic()

    def allow(self):
        now = time.monotonic()
        if now-self.start >= self.period:
            self.start, self.used = now, 0
        self.used += 1
        return self.used <= self.limit


@web.middleware
async def guard(request, handler):
    # Native Android only: reject browser-originated requests, do not enable CORS.
    if request.headers.get('Origin') is not None:
        raise web.HTTPForbidden(text='Browser clients are not supported')
    if request.path != '/health':
        policy = request.app[SECURITY]
        limits = request.app[LIMITS]
        # Never trust client-supplied X-Forwarded-For. Behind one reverse proxy this
        # bucket is deliberately shared; configure per-client limits at the edge.
        key = (request.remote or 'unknown', 'create' if request.path == '/rooms' else 'api')
        if key not in limits:
            for old, bucket in list(limits.items()):
                if time.monotonic()-bucket.start >= 60:
                    del limits[old]
            if len(limits) >= 4096:
                raise web.HTTPServiceUnavailable(text='Request capacity reached')
            limits[key] = Bucket(policy.creates_per_minute if key[1]=='create' else policy.http_per_minute, 60)
        if not limits[key].allow():
            raise web.HTTPTooManyRequests(text='Request limit exceeded', headers={'Retry-After':'60'})
    return await handler(request)


async def headers(request, response):
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Referrer-Policy'] = 'no-referrer'
