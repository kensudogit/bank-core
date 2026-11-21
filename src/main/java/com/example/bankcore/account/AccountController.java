package com.example.bankcore.account;

import com.example.bankcore.ledger.JournalRepository;
import com.example.bankcore.ledger.JournalRepository.StatementLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    public record OpenAccountRequest(
            @NotBlank @Size(max = 100) String ownerName,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @PositiveOrZero Long overdraftLimitMinor) {
    }

    public record ChangeStatusRequest(@NotNull AccountStatus status) {
    }

    public record AccountResponse(
            long id, String accountNumber, String ownerName, String currency,
            long postedBalanceMinor, long heldBalanceMinor, long overdraftLimitMinor,
            long availableBalanceMinor, AccountStatus status, long version) {

        static AccountResponse of(Account a) {
            return new AccountResponse(a.id(), a.accountNumber(), a.ownerName(), a.currency(),
                    a.postedBalanceMinor(), a.heldBalanceMinor(), a.overdraftLimitMinor(),
                    a.availableBalanceMinor(), a.status(), a.version());
        }
    }

    private final AccountService service;
    private final JournalRepository journal;

    public AccountController(AccountService service, JournalRepository journal) {
        this.service = service;
        this.journal = journal;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest req) {
        long overdraft = req.overdraftLimitMinor() == null ? 0 : req.overdraftLimitMinor();
        return AccountResponse.of(service.open(req.ownerName(), req.currency(), overdraft));
    }

    @GetMapping
    public List<AccountResponse> list() {
        return service.list().stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable long id) {
        return AccountResponse.of(service.get(id));
    }

    @PostMapping("/{id}/status")
    public AccountResponse changeStatus(@PathVariable long id, @Valid @RequestBody ChangeStatusRequest req) {
        return AccountResponse.of(service.changeStatus(id, req.status()));
    }

    /** 口座の入出金明細（仕訳明細から導出）。 */
    @GetMapping("/{id}/statement")
    public List<StatementLine> statement(@PathVariable long id) {
        service.get(id);
        return journal.statement(id);
    }
}
