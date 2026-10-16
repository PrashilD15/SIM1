export { C2 } from "./c2.js";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Credentials": "true",
  "Access-Control-Allow-Headers": "Content-Type, X-Admin-Token, X-Sim1-Auth",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Max-Age": "86400",
};

function withCors(resp) {
  const r = new Response(resp.body, resp);
  for (const k in CORS) r.headers.set(k, CORS[k]);
  return r;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const p = url.pathname;

    if (request.method === "OPTIONS") {
      return withCors(new Response(null, { status: 204 }));
    }

    if (p === "/healthz") {
      return withCors(Response.json({ ok: true, ts: Date.now() / 1000 }));
    }

    if (p.startsWith("/api/") || p.startsWith("/ws/")) {
      const id = env.C2.idFromName("global");
      const stub = env.C2.get(id);
      const resp = await stub.fetch(request);
      // WebSocket upgrades carry a non-standard `webSocket` prop; return as-is.
      if (resp.status === 101 || resp.webSocket) return resp;
      return withCors(resp);
    }

    return env.ASSETS.fetch(request);
  },
};
