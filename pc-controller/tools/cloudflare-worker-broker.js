/**
 * AnoLink Signaling & Matchmaker Broker (Zero-Dependency Edition)
 * Host: super-river-3234.parthmisar777.workers.dev
 * Quota: 100,000 free requests/day (0 KV writes, 0 external dependencies)
 */

const store = new Map();

function cleanupExpired() {
    const now = Date.now();
    for (const [key, item] of store.entries()) {
        if (now - item.timestamp > 30 * 60 * 1000) { // 30 minutes TTL
            store.delete(key);
        }
    }
}

const CORS_HEADERS = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': '*',
    'Content-Type': 'application/json'
};

export default {
    async fetch(request, env, ctx) {
        if (request.method === 'OPTIONS') {
            return new Response(null, { headers: CORS_HEADERS });
        }

        const url = new URL(request.url);
        const path = url.pathname;

        // Health check endpoint
        if (path === '/' || path === '/health') {
            cleanupExpired();
            return new Response(JSON.stringify({
                status: 'AnoLink Signal Worker Active',
                engine: 'v8-in-memory-zero-deps',
                activeEntries: store.size,
                timestamp: new Date().toISOString()
            }), { headers: CORS_HEADERS });
        }

        // Signaling endpoints: /sig/:pin/:role (role is 'phone' or 'pc')
        const sigMatch = path.match(/^\/sig\/([0-9a-zA-Z_-]+)\/([a-zA-Z0-9_-]+)/);
        if (sigMatch) {
            const pin = sigMatch[1];
            const role = sigMatch[2];
            const key = `${pin}:${role}`;

            cleanupExpired();

            // POST: Store signaling packet (Overwrites immediately with fresh active data)
            if (request.method === 'POST') {
                try {
                    const body = await request.text();
                    store.set(key, {
                        data: body,
                        timestamp: Date.now()
                    });

                    // Optional KV sync if bound (safely wrapped in try/catch to prevent Error 1101)
                    if (env && env.KV) {
                        try {
                            await env.KV.put(key, body, { expirationTtl: 1800 });
                        } catch (_) {}
                    }

                    return new Response(JSON.stringify({ success: true, key }), {
                        status: 200,
                        headers: CORS_HEADERS
                    });
                } catch (err) {
                    return new Response(JSON.stringify({ error: err.message }), {
                        status: 500,
                        headers: CORS_HEADERS
                    });
                }
            }

            // GET: Retrieve latest packet
            if (request.method === 'GET') {
                // 1. Check in-memory store first (<1ms)
                if (store.has(key)) {
                    return new Response(store.get(key).data, {
                        status: 200,
                        headers: CORS_HEADERS
                    });
                }

                // 2. Check KV if bound
                if (env && env.KV) {
                    try {
                        const val = await env.KV.get(key);
                        if (val) {
                            return new Response(val, {
                                status: 200,
                                headers: CORS_HEADERS
                            });
                        }
                    } catch (_) {}
                }

                return new Response('null', { status: 200, headers: CORS_HEADERS });
            }
        }

        return new Response('Not Found', { status: 404, headers: CORS_HEADERS });
    }
};
