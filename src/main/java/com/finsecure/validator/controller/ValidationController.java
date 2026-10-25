package com.finsecure.validator.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.finsecure.validator.model.TransactionRequest;
import com.finsecure.validator.model.ValidationResult;
import com.finsecure.validator.service.TransactionValidator;

@RestController
public class ValidationController {

    private final TransactionValidator validator;

    public ValidationController(TransactionValidator validator) {
        this.validator = validator;
    }

    /** Returns 200 for an approved transaction and 422 for a rejected one. */
    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(@RequestBody TransactionRequest request) {
        ValidationResult result = validator.validate(request);
        HttpStatus status = result.valid() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }
}
