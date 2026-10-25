package com.finsecure.validator.model;

public record ValidationError(String code, String field, String message) {
}
