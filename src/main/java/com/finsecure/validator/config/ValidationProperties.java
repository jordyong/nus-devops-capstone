package com.finsecure.validator.config;

import java.math.BigDecimal;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable validation limits, bound from the {@code validation.*} properties.
 */
@ConfigurationProperties(prefix = "validation")
public record ValidationProperties(BigDecimal maxAmount, Set<String> supportedCurrencies) {

    /** Defensive, immutable copy so the configured currencies can't be changed after binding. */
    public ValidationProperties {
        supportedCurrencies = supportedCurrencies == null ? Set.of() : Set.copyOf(supportedCurrencies);
    }
}
