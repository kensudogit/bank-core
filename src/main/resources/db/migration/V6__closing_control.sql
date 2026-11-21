-- =====================================================================
-- V6: 日次締めの運用統制（ステップ管理・照合ゲート・承認・修復・監査）
--
-- 「バッチが正常終了したこと」と「勘定が正しいこと」は別の条件として扱う。
-- 締めは 各ステップの完了 → 照合（残高・仕訳・取引件数・補助元帳）の合格 → 承認 の順で確定し、
-- そのときだけ業務日付を進める。
-- =====================================================================

-- 締めの状態
--   RUNNING            ステップ実行中（途中で失敗した場合も last_error を残してこの状態のまま。再実行で続きから）
--   RECON_FAILED       全ステップは終わったが照合が不一致。修復して再実行するまで日付は進まない
--   AWAITING_APPROVAL  照合に合格し、承認待ち
--   REJECTED           承認者が差し戻した。修復して再実行する
--   COMPLETED          承認済み（業務日付を進めた）
ALTER TABLE daily_closings DROP CONSTRAINT daily_closings_status_check;
ALTER TABLE daily_closings ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE daily_closings ADD CONSTRAINT daily_closings_status_check
    CHECK (status IN ('RUNNING', 'RECON_FAILED', 'AWAITING_APPROVAL', 'REJECTED', 'COMPLETED'));

ALTER TABLE daily_closings
    ADD COLUMN operator          VARCHAR(50),
    ADD COLUMN approved_by       VARCHAR(50),
    ADD COLUMN approved_at       TIMESTAMPTZ,
    ADD COLUMN reconciliation_ok BOOLEAN,
    ADD COLUMN reconciliation    TEXT,
    -- 取引件数の照合結果：その業務日付に記帳された仕訳の件数・借方合計と、完了した取引の件数
    ADD COLUMN journal_count     INT,
    ADD COLUMN transaction_count INT,
    ADD COLUMN debit_total_minor BIGINT;

-- 職務分離：締めを実行した人は承認できない（アプリでも検証）
ALTER TABLE daily_closings ADD CONSTRAINT ck_daily_closings_sod
    CHECK (approved_by IS NULL OR operator IS NULL OR approved_by <> operator OR approved_by = 'AUTO');

-- 締めのステップごとの実行記録（どこまで確定し、どこから再開するかを特定する）
CREATE TABLE closing_steps (
    business_date  DATE        NOT NULL REFERENCES daily_closings (business_date),
    step_no        INT         NOT NULL,
    step_name      VARCHAR(30) NOT NULL,
    status         VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    attempts       INT         NOT NULL DEFAULT 1,
    processed      INT         NOT NULL DEFAULT 0,
    detail         TEXT,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    PRIMARY KEY (business_date, step_name)
);

-- 締めの操作の監査ログ（実行・承認・差戻し・修復・ステップの再実行指示）。追記のみ
CREATE TABLE closing_audit (
    id             BIGSERIAL   PRIMARY KEY,
    business_date  DATE        NOT NULL,
    action         VARCHAR(20) NOT NULL CHECK (action IN ('RUN', 'STEP_FAILED', 'RECON_FAILED', 'AWAITING_APPROVAL',
                                                          'APPROVE', 'REJECT', 'REPAIR', 'STEP_RESET', 'COMPLETED')),
    actor          VARCHAR(50) NOT NULL,
    detail         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_closing_audit_date ON closing_audit (business_date, id);

CREATE TRIGGER trg_closing_audit_immutable
    BEFORE UPDATE OR DELETE ON closing_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
