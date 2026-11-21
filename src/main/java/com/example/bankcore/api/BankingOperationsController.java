package com.example.bankcore.api;

import com.example.bankcore.cards.CardService;
import com.example.bankcore.compliance.AmlService;
import com.example.bankcore.crm.Customer360Service;
import com.example.bankcore.crm.CustomerService;
import com.example.bankcore.risk.RiskService;
import com.example.bankcore.securities.SecuritiesService;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.TransactionStatus;
import com.example.bankcore.wealth.WealthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.example.bankcore.transaction.TransactionController.IDEMPOTENCY_KEY;
import static com.example.bankcore.transaction.TransactionController.REPLAYED;

/**
 * 顧客管理（CRM）・有価証券・投資信託と信託・カードと電子決済・AML・リスク管理の API。
 * 資金が動く POST は {@code Idempotency-Key} 必須で、COMPLETED → 201、REJECTED → 422。
 */
@RestController
@RequestMapping("/api")
public class BankingOperationsController {

    // ------------------------------------------------------------------ requests

    public record CustomerRequest(@NotBlank String customerType, @NotBlank @Size(max = 100) String name, String nameKana,
                                  LocalDate birthDate, String occupation, Long annualIncomeMinor,
                                  @Min(1) @Max(5) Integer riskTolerance, Integer investmentExperienceYears,
                                  String relationshipManager) {
    }

    public record SuitabilityRequest(@NotNull @Min(1) @Max(5) Integer riskTolerance, @NotNull @Min(0) Integer experienceYears) {
    }

    public record InteractionRequest(@NotBlank String channel, @NotBlank String kind, @NotBlank @Size(max = 1000) String note,
                                     @NotBlank @Size(max = 50) String staff) {
    }

    public record OpportunityRequest(@NotBlank String product, @NotBlank @Size(max = 200) String description,
                                     Long amountMinor, @NotBlank String owner, LocalDate expectedClose) {
    }

    public record TradeRequest(@NotBlank String code, @NotBlank String side, @NotNull @Positive Long quantity,
                               @NotNull @Positive BigDecimal price, @NotBlank @Size(max = 50) String trader) {
    }

    public record PriceRequest(@NotNull @Positive BigDecimal price, LocalDate date) {
    }

    public record FundOrderRequest(@NotNull Long accountId, @NotBlank String fundCode, Long amountMinor, Long units) {
    }

    public record TrustRequest(@NotNull Long accountId, @NotNull @Positive Long principalMinor,
                               @NotNull @Positive Integer termMonths, @NotNull @Min(0) Integer expectedRateBp) {
    }

    public record MerchantRequest(@NotBlank String name, @NotBlank String mcc, @NotNull Long accountId,
                                  @NotNull @Min(0) Integer feeRateBp) {
    }

    public record CardRequest(@NotNull Long accountId, @NotNull @Positive Long dailyLimitMinor) {
    }

    public record AuthorizationRequest(@NotNull UUID cardToken, @NotNull Long merchantId, @NotNull @Positive Long amountMinor,
                                       @NotBlank String externalRef) {
    }

    public record AmountRequest(@NotNull @Positive Long amountMinor) {
    }

    public record WalletPaymentRequest(@NotNull Long merchantId, @NotNull @Positive Long amountMinor) {
    }

    public record AlertActionRequest(@NotBlank @Size(max = 50) String by, @Size(max = 1000) String note,
                                     Boolean freezeAccount) {
    }

    private final CustomerService customers;
    private final Customer360Service customer360;
    private final SecuritiesService securities;
    private final WealthService wealth;
    private final CardService cards;
    private final AmlService aml;
    private final RiskService risk;

    public BankingOperationsController(CustomerService customers, Customer360Service customer360,
                                       SecuritiesService securities, WealthService wealth, CardService cards,
                                       AmlService aml, RiskService risk) {
        this.customers = customers;
        this.customer360 = customer360;
        this.securities = securities;
        this.wealth = wealth;
        this.cards = cards;
        this.aml = aml;
        this.risk = risk;
    }

    // ================================================================== 顧客管理（CRM）

    @PostMapping("/customers")
    public ResponseEntity<CustomerService.Customer> register(@Valid @RequestBody CustomerRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customers.register(new CustomerService.NewCustomer(
                r.customerType(), r.name(), r.nameKana(), r.birthDate(), r.occupation(), r.annualIncomeMinor(),
                r.riskTolerance(), r.investmentExperienceYears(), r.relationshipManager())));
    }

    @GetMapping("/customers")
    public List<CustomerService.Customer> searchCustomers(@RequestParam(required = false) String q) {
        return customers.search(q);
    }

    @GetMapping("/customers/{id}")
    public Customer360Service.Customer360 customer(@PathVariable long id) {
        return customer360.view(id);
    }

    @PostMapping("/customers/{id}/kyc")
    public CustomerService.Customer kyc(@PathVariable long id, @RequestParam boolean verified) {
        return customers.decideKyc(id, verified);
    }

    @PutMapping("/customers/{id}/suitability")
    public CustomerService.Customer suitability(@PathVariable long id, @Valid @RequestBody SuitabilityRequest r) {
        return customers.updateSuitability(id, r.riskTolerance(), r.experienceYears());
    }

    @PostMapping("/customers/{id}/accounts/{accountId}")
    public CustomerService.Customer linkAccount(@PathVariable long id, @PathVariable long accountId) {
        return customers.linkAccount(id, accountId);
    }

    @PostMapping("/customers/{id}/interactions")
    public CustomerService.Interaction interaction(@PathVariable long id, @Valid @RequestBody InteractionRequest r) {
        return customers.recordInteraction(id, r.channel(), r.kind(), r.note(), r.staff());
    }

    @PostMapping("/customers/{id}/opportunities")
    public CustomerService.Opportunity opportunity(@PathVariable long id, @Valid @RequestBody OpportunityRequest r) {
        return customers.createOpportunity(id, r.product(), r.description(), r.amountMinor(), r.owner(), r.expectedClose());
    }

    @PostMapping("/opportunities/{id}/stage")
    public CustomerService.Opportunity moveOpportunity(@PathVariable long id, @RequestParam String stage) {
        return customers.moveOpportunity(id, stage);
    }

    @GetMapping("/opportunities")
    public List<CustomerService.Opportunity> pipeline(@RequestParam(required = false) String owner) {
        return customers.pipeline(owner);
    }

    // ================================================================== 有価証券

    @GetMapping("/securities")
    public List<SecuritiesService.Security> securities() {
        return securities.securities();
    }

    @PutMapping("/securities/{code}/price")
    public void price(@PathVariable String code, @Valid @RequestBody PriceRequest r) {
        securities.recordPrice(code, r.date(), r.price());
    }

    @PostMapping("/securities/trades")
    public ResponseEntity<SecuritiesService.TradeResult> trade(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                               @Valid @RequestBody TradeRequest r) {
        SecuritiesService.TradeResult result = securities.trade(r.code(), r.side(), r.quantity(), r.price(), r.trader(), key);
        return respond(result.transaction(), result.replayed(), result);
    }

    @GetMapping("/securities/trades")
    public List<SecuritiesService.Trade> trades(@RequestParam(required = false) String code) {
        return securities.trades(code);
    }

    @GetMapping("/securities/portfolio")
    public List<SecuritiesService.Position> portfolio() {
        return securities.portfolio();
    }

    // ================================================================== 投資信託・金銭信託

    @GetMapping("/funds")
    public List<WealthService.Fund> funds() {
        return wealth.funds();
    }

    @PutMapping("/funds/{code}/nav")
    public WealthService.Fund nav(@PathVariable String code, @Valid @RequestBody PriceRequest r) {
        return wealth.updateNav(code, r.price(), r.date());
    }

    @PostMapping("/fund-orders/subscriptions")
    public ResponseEntity<WealthService.OrderResult> subscribe(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                               @Valid @RequestBody FundOrderRequest r) {
        WealthService.OrderResult result = wealth.subscribe(r.accountId(), r.fundCode(),
                r.amountMinor() == null ? 0 : r.amountMinor(), key);
        return respond(result.transaction(), result.replayed(), result);
    }

    @PostMapping("/fund-orders/redemptions")
    public ResponseEntity<WealthService.OrderResult> redeem(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                            @Valid @RequestBody FundOrderRequest r) {
        WealthService.OrderResult result = wealth.redeem(r.accountId(), r.fundCode(), r.units() == null ? 0 : r.units(), key);
        return respond(result.transaction(), result.replayed(), result);
    }

    @PostMapping("/funds/{code}/settlement")
    public long settleFund(@PathVariable String code) {
        return wealth.settleSubscriptions(code);
    }

    @GetMapping("/accounts/{accountId}/funds")
    public List<WealthService.FundHolding> fundHoldings(@PathVariable long accountId) {
        return wealth.holdings(accountId);
    }

    @PostMapping("/money-trusts")
    public ResponseEntity<WealthService.TrustResult> entrust(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                             @Valid @RequestBody TrustRequest r) {
        WealthService.TrustResult result = wealth.entrust(r.accountId(), r.principalMinor(), r.termMonths(),
                r.expectedRateBp(), key);
        return respond(result.transaction(), result.replayed(), result);
    }

    @GetMapping("/money-trusts/{id}")
    public WealthService.MoneyTrust trust(@PathVariable long id) {
        return wealth.trust(id);
    }

    // ================================================================== カード・電子決済

    @PostMapping("/merchants")
    public ResponseEntity<CardService.Merchant> merchant(@Valid @RequestBody MerchantRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cards.registerMerchant(r.name(), r.mcc(), r.accountId(), r.feeRateBp()));
    }

    @PostMapping("/cards")
    public ResponseEntity<CardService.Card> issueCard(@Valid @RequestBody CardRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cards.issueCard(r.accountId(), r.dailyLimitMinor()));
    }

    @PostMapping("/cards/{id}/status")
    public CardService.Card cardStatus(@PathVariable long id, @RequestParam boolean active) {
        return cards.setCardStatus(id, active);
    }

    @PostMapping("/card-authorizations")
    public CardService.Authorization authorize(@Valid @RequestBody AuthorizationRequest r) {
        return cards.authorize(r.cardToken(), r.merchantId(), r.amountMinor(), r.externalRef());
    }

    @PostMapping("/card-authorizations/{id}/capture")
    public ResponseEntity<CardService.CaptureResult> capture(@PathVariable long id,
                                                             @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                             @Valid @RequestBody AmountRequest r) {
        CardService.CaptureResult result = cards.capture(id, r.amountMinor(), key);
        return respond(result.transaction(), result.replayed(), result);
    }

    @PostMapping("/card-authorizations/{id}/release")
    public CardService.Authorization release(@PathVariable long id) {
        return cards.release(id);
    }

    @PostMapping("/wallets")
    public ResponseEntity<CardService.Wallet> openWallet(@RequestParam long accountId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cards.openWallet(accountId));
    }

    @PostMapping("/wallets/{id}/top-ups")
    public ResponseEntity<CardService.WalletResult> topUp(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                          @Valid @RequestBody AmountRequest r) {
        CardService.WalletResult result = cards.topUp(id, r.amountMinor(), key);
        return respond(result.transaction(), result.replayed(), result);
    }

    @PostMapping("/wallets/{id}/payments")
    public ResponseEntity<CardService.WalletResult> pay(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                        @Valid @RequestBody WalletPaymentRequest r) {
        CardService.WalletResult result = cards.pay(id, r.merchantId(), r.amountMinor(), key);
        return respond(result.transaction(), result.replayed(), result);
    }

    @PostMapping("/merchant-settlements")
    public List<CardService.Settlement> settleMerchants() {
        return cards.settleMerchants();
    }

    // ================================================================== AML

    @PostMapping("/aml/scan")
    public AmlService.ScanReport scan() {
        return aml.scan();
    }

    @GetMapping("/aml/alerts")
    public List<AmlService.Alert> alerts(@RequestParam(required = false) String status) {
        return aml.alerts(status);
    }

    @PostMapping("/aml/alerts/{id}/investigation")
    public AmlService.Alert investigate(@PathVariable long id, @Valid @RequestBody AlertActionRequest r) {
        return aml.investigate(id, r.by());
    }

    @PostMapping("/aml/alerts/{id}/closure")
    public AmlService.Alert close(@PathVariable long id, @Valid @RequestBody AlertActionRequest r) {
        return aml.close(id, r.by(), r.note());
    }

    @PostMapping("/aml/alerts/{id}/report")
    public AmlService.Alert report(@PathVariable long id, @Valid @RequestBody AlertActionRequest r) {
        return aml.report(id, r.by(), r.note(), Boolean.TRUE.equals(r.freezeAccount()));
    }

    // ================================================================== リスク管理

    @GetMapping("/risk/dashboard")
    public RiskService.Dashboard riskDashboard() {
        return risk.dashboard();
    }

    @GetMapping("/risk/market")
    public RiskService.MarketRisk marketRisk() {
        return risk.market();
    }

    @GetMapping("/risk/liquidity")
    public RiskService.LiquidityRisk liquidityRisk() {
        return risk.liquidity();
    }

    private static <T> ResponseEntity<T> respond(MoneyTransaction tx, boolean replayed, T body) {
        HttpStatus status = tx.status() == TransactionStatus.COMPLETED ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).header(REPLAYED, Boolean.toString(replayed)).body(body);
    }
}
