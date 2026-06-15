package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.DailyTransaction;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DailyTransaction} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVTRA06Y staging round-trip, signed scale-2 dalytranAmt (compareTo, never equals), and
 * identity over dalytranId per AAP {@code §0.8.2}.
 */
@DisplayName("DailyTransaction entity — CVTRA06Y staging, signed scale-2 amount, identity by dalytranId")
class DailyTransactionTest {

    private DailyTransaction daily;

    @BeforeEach
    void setUp() {
        daily = new DailyTransaction();
    }

    @Test
    @DisplayName("scalar fields round-trip through getters/setters")
    void scalarFieldsRoundTrip() {
        daily.setDalytranId("0000000000000010");
        daily.setDalytranTypeCd("02");
        daily.setDalytranCatCd(7);
        daily.setDalytranSource("OPERATOR");
        daily.setDalytranDesc("ADJUSTMENT");
        daily.setDalytranMerchantId(987654321L);
        daily.setDalytranMerchantName("GLOBAL CORP");
        daily.setDalytranMerchantCity("DALLAS");
        daily.setDalytranMerchantZip("75201");
        daily.setDalytranCardNum("4111111111111111");
        daily.setDalytranOrigTs("2024-02-01-00.00.00.000000");
        daily.setDalytranProcTs("2024-02-02-00.00.00.000000");

        assertThat(daily.getDalytranId()).isEqualTo("0000000000000010");
        assertThat(daily.getDalytranTypeCd()).isEqualTo("02");
        assertThat(daily.getDalytranCatCd()).isEqualTo(7);
        assertThat(daily.getDalytranSource()).isEqualTo("OPERATOR");
        assertThat(daily.getDalytranDesc()).isEqualTo("ADJUSTMENT");
        assertThat(daily.getDalytranMerchantId()).isEqualTo(987654321L);
        assertThat(daily.getDalytranMerchantName()).isEqualTo("GLOBAL CORP");
        assertThat(daily.getDalytranMerchantCity()).isEqualTo("DALLAS");
        assertThat(daily.getDalytranMerchantZip()).isEqualTo("75201");
        assertThat(daily.getDalytranCardNum()).isEqualTo("4111111111111111");
        assertThat(daily.getDalytranOrigTs()).isEqualTo("2024-02-01-00.00.00.000000");
        assertThat(daily.getDalytranProcTs()).isEqualTo("2024-02-02-00.00.00.000000");
    }

    @Test
    @DisplayName("a signed (negative) dalytranAmt preserves sign and scale 2")
    void negativeAmountPreservesSignAndScale() {
        daily.setDalytranAmt(new BigDecimal("-12.34"));
        assertThat(daily.getDalytranAmt().scale()).isEqualTo(2);
        assertThat(daily.getDalytranAmt()).isEqualByComparingTo("-12.34");
        assertThat(daily.getDalytranAmt().signum()).isEqualTo(-1);
    }

    @Test
    @DisplayName("identity is by dalytranId only; equals(null)/equals(other type) are false")
    void identityByDalytranId() {
        daily.setDalytranId("0000000000000010");
        DailyTransaction same = new DailyTransaction();
        same.setDalytranId("0000000000000010");
        DailyTransaction diff = new DailyTransaction();
        diff.setDalytranId("0000000000000011");

        assertThat(daily).isEqualTo(same);
        assertThat(daily).hasSameHashCodeAs(same);
        assertThat(daily).isNotEqualTo(diff);
        assertThat(daily.equals(null)).isFalse();
        assertThat(daily.equals("nope")).isFalse();
    }
}
