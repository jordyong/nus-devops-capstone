package com.finsecure.validator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.finsecure.validator.model.TransactionRequest;
import com.finsecure.validator.model.ValidationError;
import com.finsecure.validator.model.ValidationResult;
import com.finsecure.validator.service.TransactionValidator;

@WebMvcTest(ValidationController.class)
class ValidationControllerTest {

    private static final String BODY = """
            {"transactionId":"TXN-001","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321",
             "amount":250.50,"currency":"SGD"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionValidator validator;

    @Test
    void approvedTransactionReturns200() throws Exception {
        when(validator.validate(any(TransactionRequest.class)))
                .thenReturn(ValidationResult.of("TXN-001", List.of()));

        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void rejectedTransactionReturns422WithErrors() throws Exception {
        when(validator.validate(any(TransactionRequest.class)))
                .thenReturn(ValidationResult.of("TXN-001", List.of(new ValidationError(
                        TransactionValidator.AMOUNT_LIMIT_EXCEEDED, "amount", "amount exceeds the limit"))));

        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].code").value("AMOUNT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }
}
