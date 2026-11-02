package com.finsecure.validator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.finsecure.validator.config.ValidationProperties;
import com.finsecure.validator.model.TransactionRequest;
import com.finsecure.validator.model.ValidationError;
import com.finsecure.validator.model.ValidationResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TransactionValidatorTest {

    private static final String SOURCE = "ACC12345678";
    private static final String DESTINATION = "ACC87654321";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final TransactionValidator validator = new TransactionValidator(
            new ValidationProperties(new BigDecimal("10000.00"), Set.of("SGD", "USD", "EUR")), meterRegistry);

    private static TransactionRequest request(String source, String destination, String amount, String currency) {
        return new TransactionRequest("TXN-001", source, destination,
                amount == null ? null : new BigDecimal(amount), currency);
    }

    private static TransactionRequest validRequest() {
        return request(SOURCE, DESTINATION, "250.50", "SGD");
    }

    private static Set<String> codes(ValidationResult result) {
        return Set.copyOf(result.errors().stream().map(ValidationError::code).toList());
    }

    @Test
    void approvesValidTransaction() {
        ValidationResult result = validator.validate(validRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo(ValidationResult.Status.APPROVED);
        assertThat(result.errors()).isEmpty();
        assertThat(result.transactionId()).isEqualTo("TXN-001");
    }

    @Test
    void approvesAmountExactlyAtLimit() {
        ValidationResult result = validator.validate(request(SOURCE, DESTINATION, "10000.00", "SGD"));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsAmountAboveLimit() {
        ValidationResult result = validator.validate(request(SOURCE, DESTINATION, "10000.01", "SGD"));

        assertThat(result.status()).isEqualTo(ValidationResult.Status.REJECTED);
        assertThat(codes(result)).containsExactly(TransactionValidator.AMOUNT_LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-250.75"})
    void rejectsZeroOrNegativeAmount(String amount) {
        ValidationResult result = validator.validate(request(SOURCE, DESTINATION, amount, "SGD"));

        assertThat(codes(result)).containsExactly(TransactionValidator.INVALID_AMOUNT);
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimalPlaces() {
        ValidationResult result = validator.validate(request(SOURCE, DESTINATION, "10.123", "SGD"));

        assertThat(codes(result)).containsExactly(TransactionValidator.INVALID_AMOUNT_PRECISION);
    }

    @Test
    void acceptsTrailingZerosBeyondTwoDecimalPlaces() {
        ValidationResult result = validator.validate(request(SOURCE, DESTINATION, "10.5000", "SGD"));

        assertThat(result.valid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JPY", "sgd", "XXX"})
    void rejectsUnsupportedCurrency(String currency) {
        ValidationResult result = validator.validate(request(SOURCE, DESTINATION, "100", currency));

        assertThat(codes(result)).containsExactly(TransactionValidator.UNSUPPORTED_CURRENCY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORT", "acc12345678", "ACC-1234-5678", "A1234567890123456789X"})
    void rejectsInvalidAccountFormat(String account) {
        ValidationResult result = validator.validate(request(account, DESTINATION, "100", "SGD"));

        assertThat(codes(result)).containsExactly(TransactionValidator.INVALID_ACCOUNT_FORMAT);
        assertThat(result.errors().get(0).field()).isEqualTo("sourceAccount");
    }

    @Test
    void rejectsTransferToSameAccount() {
        ValidationResult result = validator.validate(request(SOURCE, SOURCE, "100", "SGD"));

        assertThat(codes(result)).containsExactly(TransactionValidator.SAME_ACCOUNT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingCurrency(String currency) {
        ValidationResult result = validator.validate(request(SOURCE, DESTINATION, "100", currency));

        assertThat(codes(result)).containsExactly(TransactionValidator.MISSING_FIELD);
        assertThat(result.errors().get(0).field()).isEqualTo("currency");
    }

    @Test
    void reportsEveryMissingField() {
        ValidationResult result = validator.validate(new TransactionRequest(null, null, null, null, null));

        assertThat(result.errors())
                .extracting(ValidationError::field)
                .containsExactlyInAnyOrder("transactionId", "sourceAccount", "destinationAccount", "currency", "amount");
        assertThat(codes(result)).containsExactly(TransactionValidator.MISSING_FIELD);
    }

    @Test
    void collectsMultipleRuleFailuresAtOnce() {
        ValidationResult result = validator.validate(request("bad", "bad", "-5", "JPY"));

        assertThat(codes(result)).containsExactlyInAnyOrder(
                TransactionValidator.INVALID_AMOUNT,
                TransactionValidator.UNSUPPORTED_CURRENCY,
                TransactionValidator.INVALID_ACCOUNT_FORMAT,
                TransactionValidator.SAME_ACCOUNT);
    }

    @Test
    void countsOutcomesAndRejectionReasons() {
        validator.validate(validRequest());
        validator.validate(request(SOURCE, SOURCE, "20000.00", "JPY"));

        assertThat(meterRegistry.get("finsecure.transactions").tag("outcome", "approved").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("finsecure.transactions").tag("outcome", "rejected").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("finsecure.validation.errors").tag("code", TransactionValidator.AMOUNT_LIMIT_EXCEEDED).counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("finsecure.validation.errors").tag("code", TransactionValidator.UNSUPPORTED_CURRENCY).counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("finsecure.validation.errors").tag("code", TransactionValidator.SAME_ACCOUNT).counter().count()).isEqualTo(1);
    }
}
