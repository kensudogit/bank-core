-- =====================================================================
-- V10: 決済業務（デビットカード・電子マネー・加盟店精算）
-- =====================================================================

INSERT INTO gl_accounts (code, name, category) VALUES
    ('2430', '加盟店未払金', 'LIABILITY'),
    ('2440', '電子マネー未使用残高', 'LIABILITY'),
    ('4500', '加盟店手数料', 'INCOME');

-- 加盟店（売上代金は当行の口座へ精算する）
CREATE TABLE merchants (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    mcc           CHAR(4)      NOT NULL CHECK (mcc ~ '^[0-9]{4}$'),   -- 業種コード
    account_id    BIGINT       NOT NULL REFERENCES accounts (id),
    fee_rate_bp   INT          NOT NULL CHECK (fee_rate_bp BETWEEN 0 AND 1000)
);

-- デビットカード。カード番号は保存せず、トークンと下 4 桁だけを持つ
CREATE TABLE cards (
    id                BIGSERIAL   PRIMARY KEY,
    token             UUID        NOT NULL UNIQUE,
    last4             CHAR(4)     NOT NULL,
    account_id        BIGINT      NOT NULL REFERENCES accounts (id),
    status            VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
    daily_limit_minor BIGINT      NOT NULL CHECK (daily_limit_minor > 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cards_account ON cards (account_id);

-- オーソリ（与信）。承認額は口座の拘束額（held_balance_minor）として押さえ、売上確定または取消で外す
CREATE TABLE card_authorizations (
    id               BIGSERIAL   PRIMARY KEY,
    card_id          BIGINT      NOT NULL REFERENCES cards (id),
    merchant_id      BIGINT      NOT NULL REFERENCES merchants (id),
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    status           VARCHAR(10) NOT NULL CHECK (status IN ('AUTHORIZED', 'CAPTURED', 'RELEASED', 'DECLINED')),
    decline_reason   VARCHAR(30),
    auth_code        CHAR(6),
    external_ref     VARCHAR(40) NOT NULL,
    captured_minor   BIGINT,
    capture_transaction_id BIGINT REFERENCES transactions (id),
    business_date    DATE        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 同じ加盟店からの同じ参照番号のオーソリは 1 回だけ（通信の再送で二重に押さえない）
    CONSTRAINT uq_card_auth_ref UNIQUE (merchant_id, external_ref)
);
CREATE INDEX ix_card_auth_card ON card_authorizations (card_id, created_at);

-- 電子マネー（前払式）。残高は銀行の負債（電子マネー未使用残高）
CREATE TABLE wallets (
    id             BIGSERIAL   PRIMARY KEY,
    account_id     BIGINT      NOT NULL UNIQUE REFERENCES accounts (id),
    balance_minor  BIGINT      NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    -- 前払式支払手段の残高上限（デモ値）
    max_balance_minor BIGINT   NOT NULL DEFAULT 100000 CHECK (max_balance_minor > 0),
    status         VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    version        BIGINT      NOT NULL DEFAULT 0
);

-- 加盟店の未精算売上（カード売上・電子マネー支払）。精算で支払済みにする
CREATE TABLE merchant_receivables (
    transaction_id   BIGINT      PRIMARY KEY REFERENCES transactions (id),
    merchant_id      BIGINT      NOT NULL REFERENCES merchants (id),
    gross_minor      BIGINT      NOT NULL CHECK (gross_minor > 0),
    source           VARCHAR(10) NOT NULL CHECK (source IN ('CARD', 'WALLET')),
    settled          BOOLEAN     NOT NULL DEFAULT false,
    settlement_transaction_id BIGINT REFERENCES transactions (id)
);
CREATE INDEX ix_merchant_receivables_open ON merchant_receivables (merchant_id) WHERE NOT settled;
