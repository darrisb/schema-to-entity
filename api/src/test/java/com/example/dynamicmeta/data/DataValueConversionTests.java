package com.example.dynamicmeta.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole numbers beyond JavaScript's safe integer range (2^53 - 1) are returned as
 * strings so a browser cannot round a large BIGINT key to a different value.
 */
class DataValueConversionTests {

    private static final long MAX_SAFE = 9_007_199_254_740_991L;

    @Test
    void safeIntegersStayNumbers() {
        assertThat(DataQueryService.toJsonNumber(42)).isEqualTo(42);
        assertThat(DataQueryService.toJsonNumber(MAX_SAFE)).isEqualTo(MAX_SAFE);
        assertThat(DataQueryService.toJsonNumber(-MAX_SAFE)).isEqualTo(-MAX_SAFE);
        assertThat(DataQueryService.toJsonNumber(BigInteger.valueOf(MAX_SAFE)))
                .isEqualTo(BigInteger.valueOf(MAX_SAFE));
    }

    @Test
    void unsafeWholeNumbersBecomeStrings() {
        assertThat(DataQueryService.toJsonNumber(MAX_SAFE + 1)).isEqualTo("9007199254740992");
        assertThat(DataQueryService.toJsonNumber(Long.MIN_VALUE))
                .isEqualTo(Long.toString(Long.MIN_VALUE));
        assertThat(DataQueryService.toJsonNumber(new BigInteger("123456789012345678901234")))
                .isEqualTo("123456789012345678901234");
        assertThat(DataQueryService.toJsonNumber(new BigDecimal("9007199254740993")))
                .isEqualTo("9007199254740993");
        // NUMERIC(20,2) holding a whole value is still a whole number.
        assertThat(DataQueryService.toJsonNumber(new BigDecimal("9007199254740993.00")))
                .isEqualTo("9007199254740993.00");
    }

    @Test
    void fractionsAndSmallDecimalsStayNumbers() {
        BigDecimal price = new BigDecimal("19.99");
        assertThat(DataQueryService.toJsonNumber(price)).isSameAs(price);
        assertThat(DataQueryService.toJsonNumber(new BigDecimal("12345678901234567.5")))
                .isInstanceOf(BigDecimal.class);
        assertThat(DataQueryService.toJsonNumber(1.5d)).isEqualTo(1.5d);
    }
}
