-- SelfKaizen 集約サーバー スキーマ（Cloudflare D1 / SQLite）
--
-- 設計方針（docs/DESIGN-serverless.md より）:
--  1. 端末は複数想定。すべての行が device_id を持つ。
--  2. **冪等性**: モバイルは必ず再送する。`ingest_batch` の PRIMARY KEY で
--     同一バッチの二重取り込みを防ぐ。
--  3. **local_date は端末が確定して送る。** サーバーで日付を切ると
--     タイムゾーン・夏時間の扱いがずれる。「その端末にとっての1日」は
--     端末しか正しく判定できない。
--  4. 集計は**書き込み時**に再計算する（読み取り時に大量走査しない）。
--     これは D1 の行読み取り課金と Workers の CPU 制限に直撃しないため。

-- ---------------------------------------------------------------------------
-- 端末
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device (
    id            TEXT PRIMARY KEY,
    -- 平文トークンは保存しない。SHA-256 の hex のみ保存する。
    token_hash    TEXT NOT NULL,
    label         TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    last_seen_at  INTEGER,
    revoked       INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_device_token
    ON device (token_hash);

-- ---------------------------------------------------------------------------
-- 取り込みバッチ（冪等性の単位）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ingest_batch (
    id           TEXT PRIMARY KEY,          -- クライアント生成の UUID
    device_id    TEXT NOT NULL REFERENCES device (id),
    received_at  INTEGER NOT NULL,
    event_count  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_batch_device
    ON ingest_batch (device_id, received_at);

-- ---------------------------------------------------------------------------
-- 生イベント（端末が日単位に分割済みの区間）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS usage_event (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id     TEXT NOT NULL REFERENCES device (id),
    batch_id      TEXT NOT NULL REFERENCES ingest_batch (id),
    -- 端末が確定したローカル日付 "YYYY-MM-DD"
    local_date    TEXT NOT NULL,
    package_name  TEXT NOT NULL,
    app_label     TEXT NOT NULL,
    start_time    INTEGER NOT NULL,
    end_time      INTEGER NOT NULL,
    close_reason  TEXT,
    -- 再送で同じ区間が増えないための一意制約。
    -- Android 側の app_usage_event と同じ考え方。
    UNIQUE (device_id, package_name, start_time)
);

CREATE INDEX IF NOT EXISTS idx_event_device_date
    ON usage_event (device_id, local_date);

-- ---------------------------------------------------------------------------
-- 日次集計（書き込み時に usage_event から再計算する）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS daily_summary (
    device_id     TEXT NOT NULL REFERENCES device (id),
    local_date    TEXT NOT NULL,
    package_name  TEXT NOT NULL,
    app_label     TEXT NOT NULL,
    total_millis  INTEGER NOT NULL,
    -- 記録された区間の数（アプリの起動回数ではなく「区間数」）
    segment_count INTEGER NOT NULL,
    PRIMARY KEY (device_id, local_date, package_name)
);

CREATE INDEX IF NOT EXISTS idx_summary_device_date
    ON daily_summary (device_id, local_date);
