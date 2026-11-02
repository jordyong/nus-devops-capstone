package com.finsecure.validator.model;

import java.util.List;

public record ValidationResult(String transactionId, boolean valid, Status status, List<ValidationError> errors) {

    public enum Status { APPROVED, REJECTED }

    /** Defensive, immutable copy so callers can't mutate the result's error list. */
    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ValidationResult of(String transactionId, List<ValidationError> errors) {
        boolean valid = errors.isEmpty();
        return new ValidationResult(transactionId, valid, valid ? Status.APPROVED : Status.REJECTED, errors);
    }
}
