-- =====================================================================
-- V11: コンプライアンス（AML・不正取引の監視、アラートの案件管理、疑わしい取引の届出）
-- =====================================================================

CREATE TABLE aml_alerts (
    id               BIGSERIAL    PRIMARY KEY,
    rule             VARCHAR(30)  NOT NULL,
    customer_id      BIGINT       REFERENCES customers (id),
    account_id       BIGINT       REFERENCES accounts (id),
    -- 同じルール・同じ参照（取引・口座と日付など）のアラートは 1 件だけ（監視を再実行しても重複させない）
    reference        VARCHAR(80)  NOT NULL,
    score            INT          NOT NULL CHECK (score BETWEEN 0 AND 100),
    detail           VARCHAR(400) NOT NULL,
    status           VARCHAR(15)  NOT NULL DEFAULT 'OPEN'
                     CHECK (status IN ('OPEN', 'INVESTIGATING', 'CLOSED', 'REPORTED')),
    assigned_to      VARCHAR(50),
    resolution_note  VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_aml_alerts UNIQUE (rule, reference),
    -- 完了（問題なし・届出済み）には判断理由が必要
    CONSTRAINT ck_aml_resolution CHECK (status NOT IN ('CLOSED', 'REPORTED') OR resolution_note IS NOT NULL)
);
CREATE INDEX ix_aml_alerts_status ON aml_alerts (status, created_at);
CREATE INDEX ix_aml_alerts_account ON aml_alerts (account_id);

-- 監視の進捗（どの取引・オーソリまで検査したか）。1 行だけ
CREATE TABLE aml_scan_cursor (
    id                    SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_transaction_id   BIGINT   NOT NULL DEFAULT 0,
    last_authorization_id BIGINT   NOT NULL DEFAULT 0,
    last_foreign_id       BIGINT   NOT NULL DEFAULT 0
);
INSERT INTO aml_scan_cursor (id) VALUES (1);

-- 疑わしい取引の届出（記録。実際の届出は所管行政庁へのシステムで行う）
CREATE TABLE suspicious_transaction_reports (
    id           BIGSERIAL     PRIMARY KEY,
    alert_id     BIGINT        NOT NULL UNIQUE REFERENCES aml_alerts (id),
    customer_id  BIGINT        REFERENCES customers (id),
    account_id   BIGINT        REFERENCES accounts (id),
    summary      VARCHAR(1000) NOT NULL,
    reported_by  VARCHAR(50)   NOT NULL,
    reported_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
