package com.example.bankcore.transaction;

public enum TransactionType {
    /** 行内振込：振込元口座 → 振込先口座 */
    TRANSFER,
    /** 現金入金：現金 → 口座 */
    DEPOSIT,
    /** 現金出金：口座 → 現金 */
    WITHDRAWAL,
    /** 利息計上：支払利息（費用）→ 口座。日次締めで計上する */
    INTEREST,
    /** 取消：元取引の仕訳の貸借を入れ替えた反対仕訳を記帳する */
    REVERSAL,
    /** 融資実行：貸出金 → 預金口座（{@code LoanService} が記帳する） */
    LOAN_DISBURSEMENT,
    /** 融資の返済：預金口座 → 貸出金・利息・遅延損害金 */
    LOAN_REPAYMENT,
    /** 貸倒引当金の繰入れ・戻入れ（延滞管理で計上） */
    LOAN_PROVISION,
    /** 貸出金の償却（貸倒引当金の取崩しと貸倒損失） */
    LOAN_WRITE_OFF,
    /** 口座振替：契約者の口座 → 収納企業の口座（{@code PaymentService}） */
    DIRECT_DEBIT,
    /** 国内為替の仕向（他行宛振込）：口座 → 未決済為替借（全銀で送信） */
    REMITTANCE_OUT,
    /** 国内為替の被仕向（他行からの振込）：日銀預け金 → 口座（または仮受金で保留） */
    REMITTANCE_IN,
    /** 仕向送金の取消・組戻しによる返金 */
    REMITTANCE_RETURN,
    /** 全銀データの決済：未決済為替借 → 日銀預け金 */
    CLEARING_SETTLEMENT,
    /** 両替（円 ⇔ 外貨）：外国為替ポジションを介して通貨ごとに貸借を一致させる */
    FX_EXCHANGE,
    /** 海外送金（仕向） */
    FOREIGN_REMITTANCE,
    /** 海外送金の謝絶による返金 */
    FOREIGN_REFUND,
    /** 有価証券の売買・償還（銀行自身の運用。{@code SecuritiesService}） */
    SECURITIES_TRADE,
    /** 有価証券の利金 */
    SECURITIES_INCOME,
    /** 有価証券の時価評価（その他有価証券評価差額金の洗替） */
    SECURITIES_VALUATION,
    /** 投資信託の購入（募集）：口座 → 投信未払金・販売手数料 */
    FUND_SUBSCRIPTION,
    /** 投資信託の解約：解約代金を口座へ入金 */
    FUND_REDEMPTION,
    /** 投資信託の購入代金を受託銀行へ払い込む */
    FUND_SETTLEMENT,
    /** 金銭信託の受託：口座 → 信託勘定 */
    TRUST_ENTRUST,
    /** 金銭信託の満期払出し（元本 + 配当） */
    TRUST_PAYOUT,
    /** デビットカード売上の確定：口座 → 加盟店未払金 */
    CARD_CAPTURE,
    /** 加盟店への売上代金の支払 */
    MERCHANT_SETTLEMENT,
    /** 電子マネーへのチャージ：口座 → 電子マネー残高 */
    WALLET_TOPUP,
    /** 電子マネーでの支払：電子マネー残高 → 加盟店未払金 */
    WALLET_PAYMENT;

    /** 融資の取引か。 */
    public boolean isLoan() {
        return name().startsWith("LOAN_");
    }

    /**
     * 資金移動 API（{@code MoneyMovementService}）で実行・取消できる種別か。
     * それ以外は融資・為替など各業務のサービスが、業務データ（融資残高・送金の状態など）と一緒に記帳するため、
     * 仕訳だけを反対にする取消はできない。
     */
    public boolean isGenericMovement() {
        return switch (this) {
            case TRANSFER, DEPOSIT, WITHDRAWAL, INTEREST, REVERSAL -> true;
            default -> false;
        };
    }
}
