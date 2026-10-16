export { C2 } from "./c2.js";

// Reflected-origin CORS. A wildcard "*" cannot be paired with credentials, so
// the panel origin is echoed back. This lets the panel be served from a DIFFERENT
// public origin (Vercel, GitHub Pages, another host) and still reach this API
// with the X-Admin-Token header and the admin cookie.
const CORS_BASE = {
  "Access-Control-Allow-Credentials": "true",
  "Access-Control-Allow-Headers": "Content-Type, X-Admin-Token, X-Sim1-Auth",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Max-Age": "86400",
  "Vary": "Origin",
};

function corsHeaders(request) {
  const h = { ...CORS_BASE };
  const origin = request.headers.get("Origin");
  if (origin) h["Access-Control-Allow-Origin"] = origin;
  return h;
}

function withCors(resp, request) {
  const r = new Response(resp.body, resp);
  const h = corsHeaders(request);
  for (const k in h) r.headers.set(k, h[k]);
  return r;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const p = url.pathname;

    if (request.method === "OPTIONS") {
      return withCors(new Response(null, { status: 204 }), request);
    }

    if (p === "/healthz") {
      return withCors(Response.json({ ok: true, ts: Date.now() / 1000 }), request);
    }

    if (p.startsWith("/api/") || p.startsWith("/ws/")) {
      const id = env.C2.idFromName("global");
      const stub = env.C2.get(id);
      const resp = await stub.fetch(request);
      // WebSocket upgrades carry a non-standard `webSocket` prop; return as-is.
      if (resp.status === 101 || resp.webSocket) return resp;
      return withCors(resp, request);
    }

    return env.ASSETS.fetch(request);
  },
};
