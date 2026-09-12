-- ペアコードに用途を持たせる。
--
-- コードは「ブラウザのログイン」と「端末の登録」の2通りに使う。
-- **用途を分ける理由:** 端末コードは端末トークン（＝書き込み権限）を配るので、
-- 閲覧用のブラウザコードより影響が強い。取り違えると
-- 「ブラウザ用に配ったつもりのコードで端末が増える」ことになる。
--
-- 既存行はすべてブラウザ用（この列を足す前に発行されたもの）なので既定値でよい。
ALTER TABLE pair_code ADD COLUMN kind TEXT NOT NULL DEFAULT 'browser';

CREATE INDEX IF NOT EXISTS idx_pair_kind
    ON pair_code (kind, expires_at);
