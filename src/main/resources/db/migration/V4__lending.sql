-- =====================================================================
-- V4: 融資（与信審査・融資実行・貸付元帳・返済・担保保証・延滞管理・引当・償却）
-- 金額は既存テーブルと同じく BIGINT の最小通貨単位。金利は bp（100 = 1.00%）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 勘定科目の追加
--   貸出金・貸倒引当金は融資（loans.id）を補助科目として持つ（journal_lines.loan_id）。
--   貸倒引当金は資産の評価勘定（貸方残高）。
-- ---------------------------------------------------------------------
INSERT INTO gl_accounts (code, name, category) VALUES
    ('1300', '貸出金', 'ASSET'),
    ('1390', '貸倒引当金', 'ASSET'),
    ('4100', '貸出金利息', 'INCOME'),
    ('4110', '遅延損害金', 'INCOME'),
    ('5100', '貸倒引当金繰入額', 'EXPENSE'),
    ('5110', '貸倒損失', 'EXPENSE');

-- ---------------------------------------------------------------------
-- 融資申込と与信審査の結果
-- ---------------------------------------------------------------------
CREATE TABLE loan_applications (
    id                         BIGSERIAL    PRIMARY KEY,
    account_id                 BIGINT       NOT NULL REFERENCES accounts (id),
    product                    VARCHAR(20)  NOT NULL CHECK (product IN ('HOUSING', 'BUSINESS', 'CONSUMER')),
    principal_minor            BIGINT       NOT NULL CHECK (principal_minor > 0),
    term_months                INT          NOT NULL CHECK (term_months BETWEEN 1 AND 600),
    repayment_method           VARCHAR(20)  NOT NULL CHECK (repayment_method IN ('EQUAL_PAYMENT', 'EQUAL_PRINCIPAL')),
    annual_income_minor        BIGINT       NOT NULL CHECK (annual_income_minor >= 0),
    existing_annual_debt_minor BIGINT       NOT NULL DEFAULT 0 CHECK (existing_annual_debt_minor >= 0),
    purpose                    VARCHAR(200),
    status                     VARCHAR(20)  NOT NULL
                               CHECK (status IN ('SUBMITTED', 'SCREENED', 'APPROVED', 'REJECTED', 'DISBURSED')),
    submitted_by               VARCHAR(50)  NOT NULL,
    submitted_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 与信審査（スコアリング）の結果
    score                      INT,
    grade                      CHAR(1)      CHECK (grade IN ('A', 'B', 'C', 'D', 'E')),
    dti_bp                     INT,
    coverage_bp                INT,
    proposed_rate_bp           INT,
    recommendation             VARCHAR(10)  CHECK (recommendation IN ('APPROVE', 'REFER', 'DECLINE')),
    screening_reasons          TEXT,
    screened_at                TIMESTAMPTZ,
    -- 決裁
    approved_rate_bp           INT          CHECK (approved_rate_bp BETWEEN 0 AND 1500),
    decided_by                 VARCHAR(50),
    decision_note              VARCHAR(400),
    decided_at                 TIMESTAMPTZ,
    -- 職務分離：申込の受付者は自分で決裁できない（アプリでも検証）
    CONSTRAINT ck_loan_applications_sod CHECK (decided_by IS NULL OR decided_by <> submitted_by)
);
CREATE INDEX ix_loan_applications_account ON loan_applications (account_id);

-- ---------------------------------------------------------------------
-- 担保と保証（申込に紐づく。融資実行後も追加・再評価できる）
-- ---------------------------------------------------------------------
CREATE TABLE collaterals (
    id              BIGSERIAL    PRIMARY KEY,
    application_id  BIGINT       NOT NULL REFERENCES loan_applications (id),
    type            VARCHAR(20)  NOT NULL CHECK (type IN ('REAL_ESTATE', 'DEPOSIT', 'SECURITIES')),
    description     VARCHAR(200) NOT NULL,
    appraisal_minor BIGINT       NOT NULL CHECK (appraisal_minor > 0),
    -- 担保掛目（評価額のうち回収に充てられると見る割合）。登録時の種類ごとの値を保存する
    haircut_bp      INT          NOT NULL CHECK (haircut_bp BETWEEN 0 AND 10000),
    lien_rank       INT          CHECK (lien_rank BETWEEN 1 AND 9),
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RELEASED')),
    appraised_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    registered_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_collaterals_application ON collaterals (application_id);

CREATE TABLE guarantees (
    id              BIGSERIAL    PRIMARY KEY,
    application_id  BIGINT       NOT NULL REFERENCES loan_applications (id),
    type            VARCHAR(20)  NOT NULL CHECK (type IN ('GUARANTEE_COMPANY', 'INDIVIDUAL')),
    guarantor_name  VARCHAR(100) NOT NULL,
    coverage_minor  BIGINT       NOT NULL CHECK (coverage_minor > 0),
    -- 保全として認める割合（保証会社 100%、個人保証 50%）
    recognition_bp  INT          NOT NULL CHECK (recognition_bp BETWEEN 0 AND 10000),
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RELEASED')),
    registered_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_guarantees_application ON guarantees (application_id);

-- ---------------------------------------------------------------------
-- 融資（貸出金の補助元帳）
-- ---------------------------------------------------------------------
CREATE TABLE loans (
    id                BIGSERIAL    PRIMARY KEY,
    application_id    BIGINT       NOT NULL UNIQUE REFERENCES loan_applications (id),
    account_id        BIGINT       NOT NULL REFERENCES accounts (id),
    product           VARCHAR(20)  NOT NULL,
    currency          CHAR(3)      NOT NULL,
    principal_minor   BIGINT       NOT NULL CHECK (principal_minor > 0),
    outstanding_minor BIGINT       NOT NULL CHECK (outstanding_minor >= 0),
    annual_rate_bp    INT          NOT NULL CHECK (annual_rate_bp BETWEEN 0 AND 1500),
    term_months       INT          NOT NULL,
    repayment_method  VARCHAR(20)  NOT NULL,
    grade             CHAR(1),
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'DELINQUENT', 'PAID_OFF', 'WRITTEN_OFF')),
    days_past_due     INT          NOT NULL DEFAULT 0 CHECK (days_past_due >= 0),
    borrower_class    VARCHAR(20)  NOT NULL DEFAULT 'NORMAL'
                      CHECK (borrower_class IN ('NORMAL', 'WATCH', 'SPECIAL_ATTENTION', 'DOUBTFUL', 'DEFACTO_BANKRUPT')),
    provision_minor   BIGINT       NOT NULL DEFAULT 0 CHECK (provision_minor >= 0),
    disbursed_on      DATE         NOT NULL,
    maturity_date     DATE         NOT NULL,
    closed_on         DATE,
    version           BIGINT       NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 完済・償却済みの融資に残高や引当が残らない
    CONSTRAINT ck_loans_closed CHECK (status NOT IN ('PAID_OFF', 'WRITTEN_OFF')
                                      OR (outstanding_minor = 0 AND provision_minor = 0))
);
CREATE INDEX ix_loans_account ON loans (account_id);
CREATE INDEX ix_loans_status ON loans (status);

-- 返済予定表。延滞は「期日 < 業務日付 かつ未完済」で判定し、状態としては持たない
CREATE TABLE loan_installments (
    loan_id         BIGINT      NOT NULL REFERENCES loans (id),
    installment_no  INT         NOT NULL CHECK (installment_no > 0),
    due_date        DATE        NOT NULL,
    principal_due   BIGINT      NOT NULL CHECK (principal_due >= 0),
    interest_due    BIGINT      NOT NULL CHECK (interest_due >= 0),
    principal_paid  BIGINT      NOT NULL DEFAULT 0,
    interest_paid   BIGINT      NOT NULL DEFAULT 0,
    late_fee_paid   BIGINT      NOT NULL DEFAULT 0 CHECK (late_fee_paid >= 0),
    status          VARCHAR(10) NOT NULL DEFAULT 'SCHEDULED'
                    CHECK (status IN ('SCHEDULED', 'PARTIAL', 'PAID', 'CANCELLED')),
    paid_on         DATE,
    PRIMARY KEY (loan_id, installment_no),
    CONSTRAINT ck_installments_paid CHECK (principal_paid BETWEEN 0 AND principal_due
                                           AND interest_paid BETWEEN 0 AND interest_due)
);
CREATE INDEX ix_installments_due ON loan_installments (loan_id, due_date) WHERE status IN ('SCHEDULED', 'PARTIAL');

-- ---------------------------------------------------------------------
-- 取引（冪等キー）・仕訳との接続
-- ---------------------------------------------------------------------
ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'INTEREST',
                    'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'LOAN_PROVISION', 'LOAN_WRITE_OFF'));

ALTER TABLE transactions ADD COLUMN loan_id BIGINT REFERENCES loans (id);
CREATE INDEX ix_transactions_loan ON transactions (loan_id) WHERE loan_id IS NOT NULL;

ALTER TABLE transactions DROP CONSTRAINT ck_transactions_shape;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_shape CHECK (
    (type = 'TRANSFER'   AND source_account_id IS NOT NULL AND target_account_id IS NOT NULL
                         AND source_account_id <> target_account_id)
 OR (type = 'DEPOSIT'    AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 OR (type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND target_account_id IS NULL)
 OR (type = 'INTEREST'   AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 OR (type = 'REVERSAL'   AND (source_account_id IS NOT NULL OR target_account_id IS NOT NULL)
                         AND source_account_id IS DISTINCT FROM target_account_id)
 -- 融資実行：貸付金を預金口座へ入金
 OR (type = 'LOAN_DISBURSEMENT' AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 -- 返済：預金口座から引落し
 OR (type = 'LOAN_REPAYMENT'    AND source_account_id IS NOT NULL AND target_account_id IS NULL)
 -- 引当・償却：預金は動かない
 OR (type IN ('LOAN_PROVISION', 'LOAN_WRITE_OFF') AND source_account_id IS NULL AND target_account_id IS NULL)
);
-- 完了した融資取引は必ず融資に紐づき、融資以外の取引は紐づかない
-- （融資実行は予約時点では融資がまだ無いので、完了時に loan_id を設定する）
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_loan_ref CHECK (
    CASE WHEN type LIKE 'LOAN\_%' THEN status <> 'COMPLETED' OR loan_id IS NOT NULL
         ELSE loan_id IS NULL END);

-- 仕訳明細の補助科目として融資を持てるようにする（貸出金・貸倒引当金のみ、かつ必須）
ALTER TABLE journal_lines ADD COLUMN loan_id BIGINT REFERENCES loans (id);
ALTER TABLE journal_lines ADD CONSTRAINT ck_journal_lines_loan
    CHECK ((gl_code IN ('1300', '1390')) = (loan_id IS NOT NULL));
CREATE INDEX ix_journal_lines_loan ON journal_lines (loan_id) WHERE loan_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 貸付元帳：融資ごとの取引履歴（元金・利息・遅延損害金の内訳と取引後残高）
-- 仕訳と同じく追記のみ
-- ---------------------------------------------------------------------
CREATE TABLE loan_ledger (
    transaction_id          BIGINT      PRIMARY KEY REFERENCES transactions (id),
    loan_id                 BIGINT      NOT NULL REFERENCES loans (id),
    business_date           DATE        NOT NULL,
    kind                    VARCHAR(20) NOT NULL CHECK (kind IN ('DISBURSEMENT', 'REPAYMENT', 'PROVISION', 'WRITE_OFF')),
    principal_minor         BIGINT      NOT NULL DEFAULT 0 CHECK (principal_minor >= 0),
    prepaid_principal_minor BIGINT      NOT NULL DEFAULT 0 CHECK (prepaid_principal_minor >= 0),
    interest_minor          BIGINT      NOT NULL DEFAULT 0 CHECK (interest_minor >= 0),
    late_fee_minor          BIGINT      NOT NULL DEFAULT 0 CHECK (late_fee_minor >= 0),
    provision_change_minor  BIGINT      NOT NULL DEFAULT 0,
    outstanding_after_minor BIGINT      NOT NULL CHECK (outstanding_after_minor >= 0),
    provision_after_minor   BIGINT      NOT NULL CHECK (provision_after_minor >= 0),
    note                    VARCHAR(200),
    posted_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_loan_ledger_loan ON loan_ledger (loan_id, transaction_id);

CREATE TRIGGER trg_loan_ledger_immutable
    BEFORE UPDATE OR DELETE ON loan_ledger
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
