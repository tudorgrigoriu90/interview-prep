package com.example.deposits.psp;

import java.math.BigDecimal;

public interface PspClient {

    PspCharge charge(String paymentToken, BigDecimal amount, String currency, String reference);

    PspCharge lookup(String reference);
}
