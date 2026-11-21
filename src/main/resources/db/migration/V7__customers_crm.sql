-- =====================================================================
-- V7: 顧客管理（CRM）：顧客台帳・口座との紐づけ・接触履歴・営業案件
-- =====================================================================

CREATE SEQUENCE customer_number_seq START WITH 100001;

CREATE TABLE customers (
    id                BIGSERIAL    PRIMARY KEY,
    customer_number   VARCHAR(10)  NOT NULL UNIQUE,
    customer_type     VARCHAR(12)  NOT NULL CHECK (customer_type IN ('INDIVIDUAL', 'CORPORATE')),
    name              VARCHAR(100) NOT NULL,
    name_kana         VARCHAR(100),
    birth_date        DATE,
    occupation        VARCHAR(50),
    annual_income_minor BIGINT     CHECK (annual_income_minor >= 0),
    -- 投資の適合性（リスク許容度 1 低 〜 5 高）。投資信託・有価証券の販売で確認する
    risk_tolerance    INT          NOT NULL DEFAULT 1 CHECK (risk_tolerance BETWEEN 1 AND 5),
    investment_experience_years INT NOT NULL DEFAULT 0 CHECK (investment_experience_years >= 0),
    -- マネロン対策の顧客リスク評価
    aml_risk          VARCHAR(10)  NOT NULL DEFAULT 'LOW' CHECK (aml_risk IN ('LOW', 'MEDIUM', 'HIGH')),
    kyc_status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    segment           VARCHAR(20)  NOT NULL DEFAULT 'MASS' CHECK (segment IN ('MASS', 'AFFLUENT', 'PRIVATE', 'CORPORATE')),
    relationship_manager VARCHAR(50),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_customers_name ON customers (name);

-- 口座の名義人（既存口座は未紐づけのまま動く）
ALTER TABLE accounts ADD COLUMN customer_id BIGINT REFERENCES customers (id);
CREATE INDEX ix_accounts_customer ON accounts (customer_id);

-- 接触履歴（来店・電話・提案・苦情など）
CREATE TABLE customer_interactions (
    id           BIGSERIAL     PRIMARY KEY,
    customer_id  BIGINT        NOT NULL REFERENCES customers (id),
    channel      VARCHAR(10)   NOT NULL CHECK (channel IN ('BRANCH', 'PHONE', 'VISIT', 'ONLINE', 'EMAIL')),
    kind         VARCHAR(12)   NOT NULL CHECK (kind IN ('INQUIRY', 'PROPOSAL', 'COMPLAINT', 'FOLLOW_UP', 'OTHER')),
    note         VARCHAR(1000) NOT NULL,
    staff        VARCHAR(50)   NOT NULL,
    occurred_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_interactions_customer ON customer_interactions (customer_id, occurred_at DESC);

-- 営業案件（商品の提案から成約まで）
CREATE TABLE sales_opportunities (
    id             BIGSERIAL    PRIMARY KEY,
    customer_id    BIGINT       NOT NULL REFERENCES customers (id),
    product        VARCHAR(20)  NOT NULL CHECK (product IN ('DEPOSIT', 'LOAN', 'FUND', 'SECURITIES', 'CARD', 'INSURANCE', 'TRUST')),
    description    VARCHAR(200) NOT NULL,
    amount_minor   BIGINT       CHECK (amount_minor >= 0),
    stage          VARCHAR(12)  NOT NULL DEFAULT 'LEAD' CHECK (stage IN ('LEAD', 'PROPOSED', 'NEGOTIATING', 'WON', 'LOST')),
    owner          VARCHAR(50)  NOT NULL,
    expected_close DATE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_opportunities_customer ON sales_opportunities (customer_id);
CREATE INDEX ix_opportunities_stage ON sales_opportunities (stage);
