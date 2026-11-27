package com.example.dynamicmeta.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Expansion matches linked values by value even when the two columns map to different Java types. */
class ExpansionMatchKeyTests {

    private static final ColumnModel INT = new ColumnModel("id", "int4", ColumnCategory.NUMBER, false);
    private static final ColumnModel BIGINT = new ColumnModel("customer_id", "int8", ColumnCategory.NUMBER, false);
    private static final ColumnModel TEXT = new ColumnModel("code", "varchar", ColumnCategory.TEXT, false);

    @Test
    void integerAndLongWithTheSameValueMatch() {
        assertThat(DataQueryService.matchKey(5, INT))
                .isEqualTo(DataQueryService.matchKey(5L, BIGINT));
    }

    @Test
    void oracleNumberAndPostgresBigintMatch() {
        assertThat(DataQueryService.matchKey(new BigDecimal("42.00"), INT))
                .isEqualTo(DataQueryService.matchKey(42L, BIGINT))
                .isEqualTo(DataQueryService.matchKey(BigInteger.valueOf(42), BIGINT));
    }

    @Test
    void largeWholeNumberRenderedAsStringMatchesTheNumber() {
        assertThat(DataQueryService.matchKey("9007199254740993", BIGINT))
                .isEqualTo(DataQueryService.matchKey(new BigInteger("9007199254740993"), BIGINT));
    }

    @Test
    void numericLookingTextKeysStayText() {
        assertThat(DataQueryService.matchKey("007", TEXT))
                .isNotEqualTo(DataQueryService.matchKey("7", TEXT));
        assertThat(DataQueryService.matchKey("abc", TEXT)).isEqualTo("abc");
    }

    @Test
    void differentNumbersDoNotMatch() {
        assertThat(DataQueryService.matchKey(5, INT))
                .isNotEqualTo(DataQueryService.matchKey(6L, BIGINT));
    }
}
