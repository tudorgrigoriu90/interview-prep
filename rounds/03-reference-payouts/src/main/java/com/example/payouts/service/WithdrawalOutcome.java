package com.example.payouts.service;

import com.example.payouts.domain.Withdrawal;

/** @param created false when this was an idempotent replay of an earlier request (HTTP 200 instead of 201). */
public record WithdrawalOutcome(Withdrawal withdrawal, boolean created) {
}
