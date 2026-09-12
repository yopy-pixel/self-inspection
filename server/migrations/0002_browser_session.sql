-- ブラウザのログインセッションと、ペアコード。
--
-- なぜセッションを端末トークンと分けるのか:
--   以前は Cookie に**端末トークンそのもの**を入れていた。これには問題がある。
--    1. ブラウザごとに失効できない（1台止めると全部止まる／1台足すと全部同じ鍵）
--    2. そもそも失効手段が無い（Cookie を消してもサーバー側では有効なまま）
--    3. 複数の PC・スマホを区別できない（どれが生きているか分からない）
--   そこで**ブラウザごとに独立したセッショントークン**を発行する。
--   端末トークンは端末（アプリ）専用のままにする。
--
-- ペアコードの目的:
--   64桁の端末トークンを手入力させない。認証済みの主体（アプリ／既存セッション）が
--   短命・単回使用のコードを発行し、新しいブラウザはそれを引き換える。
--   **コードもハッシュだけ保存する**（DB が漏れてもログインには使えない）。

-- ---------------------------------------------------------------------------
-- ブラウザセッション（1ブラウザ = 1行）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS browser_session (
    -- 画面に出す識別子。トークンではないので表示しても安全。
    id            TEXT PRIMARY KEY,
    -- セッショントークンの SHA-256 hex。平文は保存しない。
    token_hash    TEXT NOT NULL,
    -- 画面表示用（User-Agent から作る。"Chrome on macOS" など）。
    label         TEXT NOT NULL,
    -- 発行経路: 'pair'（ペアコード） | 'token'（トークン入力） | 'legacy'（旧 Cookie の昇格）
    origin        TEXT NOT NULL DEFAULT '',
    -- 発行元の端末（分かる場合のみ）。将来の PC エージェントでも使う。
    device_id     TEXT,
    -- 発行元のセッション（ログイン済みブラウザから発行した場合）。
    origin_session_id TEXT,
    created_at    INTEGER NOT NULL,
    last_seen_at  INTEGER,
    -- 期限。使用のたびに延ばす（sliding）。
    expires_at    INTEGER NOT NULL,
    revoked_at    INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_session_token
    ON browser_session (token_hash);

CREATE INDEX IF NOT EXISTS idx_session_active
    ON browser_session (revoked_at, expires_at);

-- ---------------------------------------------------------------------------
-- ペアコード（短命・単回使用）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pair_code (
    code_hash   TEXT PRIMARY KEY,
    -- 発行元。どちらか一方（または両方）が入る。
    device_id   TEXT,
    session_id  TEXT,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    -- 引き換え済みなら時刻が入る。2回目は拒否する。
    used_at     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_pair_expires
    ON pair_code (expires_at);
