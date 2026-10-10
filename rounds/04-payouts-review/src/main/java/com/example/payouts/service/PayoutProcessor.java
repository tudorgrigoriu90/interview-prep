package com.example.payouts.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.psp.PayoutRequest;
import com.example.payouts.psp.PayoutResult;
import com.example.payouts.psp.PspPayoutClient;
import com.example.payouts.repository.WithdrawalRepository;

import static com.example.payouts.domain.WithdrawalStatus.APPROVED;

@Component
public class PayoutProcessor {

    private final WithdrawalRepository withdrawals;
    private final WithdrawalTransactions transactions;
    private final PspPayoutClient psp;

    public PayoutProcessor(WithdrawalRepository withdrawals, WithdrawalTransactions transactions, PspPayoutClient psp) {
        this.withdrawals = withdrawals;
        this.transactions = transactions;
        this.psp = psp;
    }

    @Transactional
    public void runOnce() {
        for (Withdrawal withdrawal : withdrawals.findByStatus(APPROVED)) {
            PayoutResult result = psp.payout(PayoutRequest.of(withdrawal));
            switch (result) {
                case PayoutResult.Accepted accepted -> transactions.markSent(withdrawal.getId(), accepted.pspReference());
                case PayoutResult.Declined declined -> transactions.failAndRelease(withdrawal.getId());
            }
        }
    }
}
