package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.loan.CreditScoring.Recommendation;
import com.example.bankcore.loan.LoanModels.ApplicationStatus;
import com.example.bankcore.loan.LoanModels.Collateral;
import com.example.bankcore.loan.LoanModels.Guarantee;
import com.example.bankcore.loan.LoanModels.LoanApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 融資の申込から決裁まで（与信審査システム）と、担保・保証の管理。
 *
 * <pre>
 * 申込（SUBMITTED）→ 担保・保証の登録 → 審査（SCREENED：スコア・格付・推奨・提示金利）
 *   → 決裁（APPROVED / REJECTED：受付者とは別の決裁者。謝絶推奨の承認は理由が必須）→ 融資実行（{@link LoanService}）
 * </pre>
 * 申込の状態遷移は申込行のロックで直列化する（同じ申込の審査と決裁が同時に走っても矛盾しない）。
 */
@Service
public class LoanApplicationService {

    /** 申込と、それに紐づく担保・保証。 */
    public record ApplicationView(LoanApplication application, List<Collateral> collaterals,
                                  List<Guarantee> guarantees, long securedMinor) {
    }

    public record NewApplication(long accountId, LoanProduct product, long principalMinor, int termMonths,
                                 RepaymentMethod repaymentMethod, long annualIncomeMinor, long existingAnnualDebtMinor,
                                 String purpose, String submittedBy) {
    }

    private final LoanRepository loans;
    private final AccountRepository accounts;

    public LoanApplicationService(LoanRepository loans, AccountRepository accounts) {
        this.loans = loans;
        this.accounts = accounts;
    }

    @Transactional
    public ApplicationView apply(NewApplication req) {
        Account account = accounts.findById(req.accountId())
                .orElseThrow(() -> new NotFoundException("account " + req.accountId() + " not found"));
        // 商品の金額・期間の範囲は円建てで定めているため、円口座に限る
        if (!"JPY".equals(account.currency())) {
            throw new InvalidRequestException("loans are offered for JPY accounts only");
        }
        LoanProduct p = req.product();
        if (p == null || req.repaymentMethod() == null) {
            throw new InvalidRequestException("product and repaymentMethod are required");
        }
        if (req.principalMinor() < p.minPrincipalMinor() || req.principalMinor() > p.maxPrincipalMinor()) {
            throw new InvalidRequestException(p + " principal must be between " + p.minPrincipalMinor()
                    + " and " + p.maxPrincipalMinor());
        }
        if (req.termMonths() < p.minTermMonths() || req.termMonths() > p.maxTermMonths()) {
            throw new InvalidRequestException(p + " term must be between " + p.minTermMonths()
                    + " and " + p.maxTermMonths() + " months");
        }
        if (req.annualIncomeMinor() < 0 || req.existingAnnualDebtMinor() < 0) {
            throw new InvalidRequestException("income and existing debt must not be negative");
        }
        String by = LoanCommands.requireText(req.submittedBy(), "submittedBy", 50);
        if (req.purpose() != null && req.purpose().length() > 200) {
            throw new InvalidRequestException("purpose must be at most 200 characters");
        }
        long id = loans.insertApplication(req.accountId(), p, req.principalMinor(), req.termMonths(),
                req.repaymentMethod(), req.annualIncomeMinor(), req.existingAnnualDebtMinor(), req.purpose(), by);
        return view(id);
    }

    /** 担保の登録。謝絶された申込以外（融資実行後の追加担保も含む）に登録できる。 */
    @Transactional
    public ApplicationView addCollateral(long applicationId, CollateralType type, String description,
                                         long appraisalMinor, Integer lienRank) {
        LoanApplication app = lockOpen(applicationId);
        if (type == null || appraisalMinor <= 0) {
            throw new InvalidRequestException("collateral type and a positive appraisal are required");
        }
        if (lienRank != null && (lienRank < 1 || lienRank > 9)) {
            throw new InvalidRequestException("lienRank must be between 1 and 9");
        }
        loans.insertCollateral(app.id(), type, LoanCommands.requireText(description, "description", 200),
                appraisalMinor, lienRank);
        return view(app.id());
    }

    /** 保証の登録。 */
    @Transactional
    public ApplicationView addGuarantee(long applicationId, GuaranteeType type, String guarantorName, long coverageMinor) {
        LoanApplication app = lockOpen(applicationId);
        if (type == null || coverageMinor <= 0) {
            throw new InvalidRequestException("guarantee type and a positive coverage are required");
        }
        loans.insertGuarantee(app.id(), type, LoanCommands.requireText(guarantorName, "guarantorName", 100), coverageMinor);
        return view(app.id());
    }

    /**
     * 担保の再評価（不動産価格の下落など）。保全額が変わるので、次回の延滞管理で引当額に反映される。
     */
    @Transactional
    public ApplicationView revalueCollateral(long collateralId, long appraisalMinor) {
        Collateral c = loans.findCollateral(collateralId)
                .orElseThrow(() -> new NotFoundException("collateral " + collateralId + " not found"));
        if (appraisalMinor <= 0) {
            throw new InvalidRequestException("appraisalMinor must be positive");
        }
        if (!"ACTIVE".equals(c.status())) {
            throw new BusinessConflictException("released collateral cannot be revalued");
        }
        lockOpen(c.applicationId());
        loans.revalueCollateral(collateralId, appraisalMinor);
        return view(c.applicationId());
    }

    /**
     * 与信審査（スコアリング）。担保・保証を追加した後は再審査できる（SCREENED → SCREENED）。
     */
    @Transactional
    public ApplicationView screen(long applicationId) {
        LoanApplication app = loans.lockApplication(applicationId)
                .orElseThrow(() -> new NotFoundException("loan application " + applicationId + " not found"));
        if (app.status() != ApplicationStatus.SUBMITTED && app.status() != ApplicationStatus.SCREENED) {
            throw new BusinessConflictException("application " + applicationId + " cannot be screened (status="
                    + app.status() + ")");
        }
        Account account = accounts.findById(app.accountId()).orElseThrow();
        boolean hasRealEstate = loans.collaterals(applicationId).stream()
                .anyMatch(c -> c.type() == CollateralType.REAL_ESTATE && "ACTIVE".equals(c.status()));
        CreditScoring.Result result = CreditScoring.evaluate(new CreditScoring.Input(
                app.product(), app.principalMinor(), app.termMonths(), app.repaymentMethod(), app.annualIncomeMinor(),
                app.existingAnnualDebtMinor(), loans.securedMinor(applicationId), hasRealEstate,
                account.postedBalanceMinor(), loans.countDelinquent(app.accountId())));
        loans.saveScreening(applicationId, result);
        return view(applicationId);
    }

    /**
     * 決裁。
     *
     * @param approvedRateBp 承認金利（null は審査の提示金利）
     * @throws BusinessConflictException 審査前・決裁済み、受付者本人による決裁、理由のない例外承認
     */
    @Transactional
    public ApplicationView decide(long applicationId, boolean approve, String decidedBy, String note,
                                  Integer approvedRateBp) {
        String by = LoanCommands.requireText(decidedBy, "decidedBy", 50);
        if (note != null && note.length() > 400) {
            throw new InvalidRequestException("note must be at most 400 characters");
        }
        LoanApplication app = loans.lockApplication(applicationId)
                .orElseThrow(() -> new NotFoundException("loan application " + applicationId + " not found"));
        if (app.status() != ApplicationStatus.SCREENED) {
            throw new BusinessConflictException("application " + applicationId + " must be screened before a decision"
                    + " (status=" + app.status() + ")");
        }
        // 職務分離：受付者が自分で承認すると、1 人で架空の融資を実行できてしまう
        if (by.equals(app.submittedBy())) {
            throw new BusinessConflictException("the person who submitted the application cannot decide it");
        }
        if (approve && app.recommendation() == Recommendation.DECLINE && (note == null || note.isBlank())) {
            throw new BusinessConflictException("approving a declined recommendation requires a reason (note)");
        }
        Integer rate = null;
        if (approve) {
            rate = approvedRateBp != null ? approvedRateBp : app.proposedRateBp();
            if (rate < 0 || rate > CreditScoring.MAX_RATE_BP) {
                throw new InvalidRequestException("approvedRateBp must be between 0 and " + CreditScoring.MAX_RATE_BP);
            }
        }
        loans.saveDecision(applicationId, approve, rate, by, note);
        return view(applicationId);
    }

    @Transactional(readOnly = true)
    public ApplicationView get(long applicationId) {
        return view(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> list() {
        return loans.listApplications();
    }

    private LoanApplication lockOpen(long applicationId) {
        LoanApplication app = loans.lockApplication(applicationId)
                .orElseThrow(() -> new NotFoundException("loan application " + applicationId + " not found"));
        if (app.status() == ApplicationStatus.REJECTED) {
            throw new BusinessConflictException("application " + applicationId + " was rejected");
        }
        return app;
    }

    private ApplicationView view(long id) {
        LoanApplication app = loans.findApplication(id)
                .orElseThrow(() -> new NotFoundException("loan application " + id + " not found"));
        return new ApplicationView(app, loans.collaterals(id), loans.guarantees(id), loans.securedMinor(id));
    }
}
