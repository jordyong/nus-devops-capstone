package com.finsecure.validator.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.finsecure.validator.config.ValidationProperties;
import com.finsecure.validator.model.TransactionRequest;
import com.finsecure.validator.model.ValidationError;
import com.finsecure.validator.model.ValidationResult;

/**
 * Applies the business validation rules to a transaction. All rules are evaluated so the
 * caller receives every failure at once rather than only the first.
 */
@Service
public class TransactionValidator {

    public static final String MISSING_FIELD = "MISSING_FIELD";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String INVALID_AMOUNT_PRECISION = "INVALID_AMOUNT_PRECISION";
    public static final String AMOUNT_LIMIT_EXCEEDED = "AMOUNT_LIMIT_EXCEEDED";
    public static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
    public static final String INVALID_ACCOUNT_FORMAT = "INVALID_ACCOUNT_FORMAT";
    public static final String SAME_ACCOUNT = "SAME_ACCOUNT";

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[A-Z0-9]{8,20}$");
    private static final int MAX_DECIMAL_PLACES = 2;

    private final ValidationProperties properties;

    public TransactionValidator(ValidationProperties properties) {
        this.properties = properties;
    }

    public ValidationResult validate(TransactionRequest request) {
        List<ValidationError> errors = new ArrayList<>();

        requireText(request.transactionId(), "transactionId", errors);
        boolean hasSource = requireText(request.sourceAccount(), "sourceAccount", errors);
        boolean hasDestination = requireText(request.destinationAccount(), "destinationAccount", errors);
        boolean hasCurrency = requireText(request.currency(), "currency", errors);

        if (request.amount() == null) {
            errors.add(new ValidationError(MISSING_FIELD, "amount", "amount is required"));
        } else {
            validateAmount(request.amount(), errors);
        }

        if (hasCurrency && !properties.supportedCurrencies().contains(request.currency())) {
            errors.add(new ValidationError(UNSUPPORTED_CURRENCY, "currency",
                    "currency " + request.currency() + " is not supported"));
        }

        if (hasSource) {
            validateAccountFormat(request.sourceAccount(), "sourceAccount", errors);
        }
        if (hasDestination) {
            validateAccountFormat(request.destinationAccount(), "destinationAccount", errors);
        }
        if (hasSource && hasDestination && request.sourceAccount().equals(request.destinationAccount())) {
            errors.add(new ValidationError(SAME_ACCOUNT, "destinationAccount",
                    "source and destination accounts must differ"));
        }

        return ValidationResult.of(request.transactionId(), errors);
    }

    private void validateAmount(BigDecimal amount, List<ValidationError> errors) {
        if (amount.signum() <= 0) {
            errors.add(new ValidationError(INVALID_AMOUNT, "amount", "amount must be greater than zero"));
            return;
        }
        if (amount.stripTrailingZeros().scale() > MAX_DECIMAL_PLACES) {
            errors.add(new ValidationError(INVALID_AMOUNT_PRECISION, "amount",
                    "amount must have at most " + MAX_DECIMAL_PLACES + " decimal places"));
        }
        if (amount.compareTo(properties.maxAmount()) > 0) {
            errors.add(new ValidationError(AMOUNT_LIMIT_EXCEEDED, "amount",
                    "amount exceeds the limit of " + properties.maxAmount().toPlainString()));
        }
    }

    private static void validateAccountFormat(String account, String field, List<ValidationError> errors) {
        if (!ACCOUNT_PATTERN.matcher(account).matches()) {
            errors.add(new ValidationError(INVALID_ACCOUNT_FORMAT, field,
                    field + " must be 8-20 uppercase letters or digits"));
        }
    }

    private static boolean requireText(String value, String field, List<ValidationError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new ValidationError(MISSING_FIELD, field, field + " is required"));
            return false;
        }
        return true;
    }
}
