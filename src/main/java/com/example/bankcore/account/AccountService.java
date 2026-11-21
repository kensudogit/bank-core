package com.example.bankcore.account;

import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** 口座開設。残高 0 で作成し、入金は {@code /api/deposits} から仕訳付きで行う。 */
    @Transactional
    public Account open(String ownerName, String currency, long overdraftLimitMinor) {
        if (overdraftLimitMinor < 0) {
            throw new InvalidRequestException("overdraftLimitMinor must be >= 0");
        }
        long id = accounts.insert(ownerName, currency, overdraftLimitMinor);
        return get(id);
    }

    @Transactional(readOnly = true)
    public Account get(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("account " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return accounts.findAll();
    }

    /**
     * 状態変更。残高更新と同じ行ロックを取るため、振込処理中の口座とは直列化される。
     * 解約は残高 0 のときのみ許可する。
     */
    @Transactional
    public Account changeStatus(long id, AccountStatus newStatus) {
        Account account = accounts.lockById(id)
                .orElseThrow(() -> new NotFoundException("account " + id + " not found"));
        if (account.status() == AccountStatus.CLOSED) {
            throw new InvalidRequestException("closed account cannot be changed");
        }
        if (newStatus == AccountStatus.CLOSED && account.postedBalanceMinor() != 0) {
            throw new InvalidRequestException("account balance must be zero to close");
        }
        accounts.updateStatus(id, newStatus);
        return get(id);
    }
}
