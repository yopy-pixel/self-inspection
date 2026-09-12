import { DatabaseSync } from "node:sqlite";
import type { Database, PreparedStatement } from "../src/db.ts";

/**
 * ローカル検証用の D1 互換アダプタ。
 *
 * Cloudflare のアカウントが無くても、**D1 は SQLite なので同じ SQL が動く**。
 * `node:sqlite`（Node 22 内蔵）を使うので依存はゼロ。
 *
 * これにより「本番と違う SQL をテストしていた」という事故を防ぐ。
 *
 * 注意: `node --experimental-strip-types` は TypeScript の
 * パラメータプロパティ（constructor の `private readonly x`）を解釈できない。
 * そのため明示的なフィールド代入を使っている。
 */

class SqliteStatement implements PreparedStatement {
  private readonly db: DatabaseSync;
  private readonly sql: string;
  private readonly params: unknown[];

  constructor(db: DatabaseSync, sql: string, params: unknown[] = []) {
    this.db = db;
    this.sql = sql;
    this.params = params;
  }

  bind(...values: unknown[]): PreparedStatement {
    return new SqliteStatement(this.db, this.sql, values);
  }

  async all<T = unknown>(): Promise<{ results: T[] }> {
    const rows = this.db.prepare(this.sql).all(...(this.params as never[])) as T[];
    return { results: rows };
  }

  async run(): Promise<{ meta: { changes: number } }> {
    const r = this.db.prepare(this.sql).run(...(this.params as never[]));
    // node:sqlite は bigint を返すことがあるため数値に寄せる。
    return { meta: { changes: Number(r.changes ?? 0) } };
  }

  async first<T = unknown>(): Promise<T | null> {
    const row = this.db.prepare(this.sql).get(...(this.params as never[])) as
      | T
      | undefined;
    return row ?? null;
  }
}

class SqliteDatabase implements Database {
  private readonly db: DatabaseSync;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  prepare(sql: string): PreparedStatement {
    return new SqliteStatement(this.db, sql);
  }

  async batch(statements: PreparedStatement[]): Promise<unknown[]> {
    const out: unknown[] = [];
    // D1 の batch はトランザクションで実行される。ここでも合わせる。
    this.db.exec("BEGIN");
    try {
      for (const s of statements) out.push(await s.run());
      this.db.exec("COMMIT");
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
    return out;
  }
}

/** スキーマを適用したインメモリ DB を作る。 */
export function createTestDb(schemaSql: string): Database {
  const db = new DatabaseSync(":memory:");
  // 複数ステートメントを一度に流す。
  db.exec(schemaSql);
  return new SqliteDatabase(db);
}

/** テスト用に端末を直接挿入する。 */
export async function seedDevice(
  db: Database,
  id: string,
  tokenHash: string,
  label = "test device"
): Promise<void> {
  await db
    .prepare(
      `INSERT INTO device (id, token_hash, label, created_at, last_seen_at, revoked)
       VALUES (?, ?, ?, ?, NULL, 0)`
    )
    .bind(id, tokenHash, label, Date.now())
    .run();
}
