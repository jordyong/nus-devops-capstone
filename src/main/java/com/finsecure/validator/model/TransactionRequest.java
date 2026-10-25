package com.finsecure.validator.model;

import java.math.BigDecimal;

public record TransactionRequest(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency) {
}
