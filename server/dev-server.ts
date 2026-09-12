/**
 * ローカル開発用の HTTP サーバー。
 *
 * **Cloudflare アカウント無しで、Worker の `handle()` を実際の HTTP で動かす。**
 * D1 は SQLite なので `node:sqlite` で同じ SQL が動く。
 *
 * 用途:
 *  - curl で API を叩いて挙動を確認する
 *  - Android エミュレータから `http://10.0.2.2:8787` で繋いで通信確認する
 *
 * 起動:
 *   node --experimental-strip-types dev-server.ts
 *
 * 注意: **本番の Cloudflare とは別物**（TLS 終端・CPU制限・D1 の挙動は異なる）。
 * あくまで「SQL と HTTP の契約が正しいか」を確かめるためのもの。
 */
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { createTestDb } from "./test/d1-adapter.ts";
import { handle } from "./src/index.ts";
import { registerDevice } from "./src/auth.ts";
import type { Database } from "./src/db.ts";

const here = dirname(fileURLToPath(import.meta.url));
const schema = readFileSync(join(here, "migrations", "0001_init.sql"), "utf8");

const PORT = Number(process.env.PORT ?? 8787);
const ADMIN_TOKEN = process.env.ADMIN_TOKEN ?? "local-admin-token";

const db: Database = createTestDb(schema);

// 起動時に端末を1台登録して、すぐ試せるようにする。
const { deviceId, token } = await registerDevice(db, "local-device", Date.now());

type Env = { DB: Database; ADMIN_TOKEN?: string };
const env: Env = { DB: db, ADMIN_TOKEN };

const server = createServer((req, res) => {
  void (async () => {
    try {
      const chunks: Buffer[] = [];
      for await (const chunk of req) chunks.push(chunk as Buffer);
      const body = chunks.length > 0 ? Buffer.concat(chunks) : undefined;

      const method = (req.method ?? "GET").toUpperCase();
      const headers = new Headers();
      for (const [k, v] of Object.entries(req.headers)) {
        if (typeof v === "string") headers.set(k, v);
        else if (Array.isArray(v)) headers.set(k, v.join(", "));
      }

      const request = new Request(`http://${req.headers.host ?? "localhost"}${req.url ?? "/"}`, {
        method,
        headers,
        // GET / HEAD は body を持てない。
        body: method === "GET" || method === "HEAD" ? undefined : body,
      });

      const response = await handle(request, env, Date.now());
      const text = await response.text();
      res.writeHead(response.status, Object.fromEntries(response.headers.entries()));
      res.end(text);
    } catch (e) {
      console.error("dev-server error", e);
      res.writeHead(500, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "dev-server internal error" }));
    }
  })();
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`\nSelfKaizen local dev server`);
  console.log(`  listen   : http://0.0.0.0:${PORT}`);
  console.log(`  Android  : http://10.0.2.2:${PORT}  (エミュレータから)`);
  console.log(`\n  登録済み端末:`);
  console.log(`    deviceId : ${deviceId}`);
  console.log(`    token    : ${token}`);
  console.log(`\n  動作確認:`);
  console.log(`    curl http://localhost:${PORT}/healthz`);
  console.log(`    curl -H "Authorization: Bearer ${token}" \\`);
  console.log(`      "http://localhost:${PORT}/api/v1/summary?from=2026-09-08&to=2026-09-14"\n`);
});
