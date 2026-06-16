package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.dto.CommArea;
import com.carddemo.model.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommArea}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * {@link CommArea} is the Java equivalent of the COBOL communication area
 * {@code 01 CARDDEMO-COMMAREA} (copybook {@code app/cpy/COCOM01Y.cpy}). The original
 * structure carried conversational state between pseudo-conversational CICS programs;
 * the migrated object is a plain, mutable per-request context populated incrementally.
 * These tests verify that every field round-trips through its accessor pair and that the
 * program-context constants carry the COBOL {@code 88-level} values.</p>
 */
@DisplayName("CommArea - COCOM01Y per-request context (mutable accessor round-trip)")
class CommAreaTest {

    @Test
    @DisplayName("program-context constants match COBOL 88-level values")
    void programContextConstants() {
        assertThat(CommArea.PGM_ENTER).isZero();
        assertThat(CommArea.PGM_REENTER).isEqualTo(1);
    }

    @Test
    @DisplayName("a new context exposes null/zero defaults")
    void defaultsAreUnset() {
        CommArea commArea = new CommArea();

        assertThat(commArea.getFromTranId()).isNull();
        assertThat(commArea.getFromProgram()).isNull();
        assertThat(commArea.getToTranId()).isNull();
        assertThat(commArea.getToProgram()).isNull();
        assertThat(commArea.getUserId()).isNull();
        assertThat(commArea.getUserType()).isNull();
        assertThat(commArea.getPgmContext()).isZero();
        assertThat(commArea.getCustomerId()).isNull();
        assertThat(commArea.getCustomerFirstName()).isNull();
        assertThat(commArea.getCustomerMiddleName()).isNull();
        assertThat(commArea.getCustomerLastName()).isNull();
        assertThat(commArea.getAccountId()).isNull();
        assertThat(commArea.getAccountStatus()).isNull();
        assertThat(commArea.getCardNumber()).isNull();
        assertThat(commArea.getLastMap()).isNull();
        assertThat(commArea.getLastMapset()).isNull();
    }

    @Test
    @DisplayName("every field round-trips through its setter/getter pair")
    void fieldsRoundTrip() {
        CommArea commArea = new CommArea();

        commArea.setFromTranId("CC00");
        commArea.setFromProgram("COSGN00C");
        commArea.setToTranId("CM00");
        commArea.setToProgram("COMEN01C");
        commArea.setUserId("USER0001");
        commArea.setUserType(UserType.ADMIN);
        commArea.setPgmContext(CommArea.PGM_REENTER);
        commArea.setCustomerId("000000123");
        commArea.setCustomerFirstName("ADA");
        commArea.setCustomerMiddleName("M");
        commArea.setCustomerLastName("LOVELACE");
        commArea.setAccountId("00000000123");
        commArea.setAccountStatus("Y");
        commArea.setCardNumber("4111111111111111");
        commArea.setLastMap("COMEN1A");
        commArea.setLastMapset("COMEN01");

        assertThat(commArea.getFromTranId()).isEqualTo("CC00");
        assertThat(commArea.getFromProgram()).isEqualTo("COSGN00C");
        assertThat(commArea.getToTranId()).isEqualTo("CM00");
        assertThat(commArea.getToProgram()).isEqualTo("COMEN01C");
        assertThat(commArea.getUserId()).isEqualTo("USER0001");
        assertThat(commArea.getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(commArea.getPgmContext()).isEqualTo(CommArea.PGM_REENTER);
        assertThat(commArea.getCustomerId()).isEqualTo("000000123");
        assertThat(commArea.getCustomerFirstName()).isEqualTo("ADA");
        assertThat(commArea.getCustomerMiddleName()).isEqualTo("M");
        assertThat(commArea.getCustomerLastName()).isEqualTo("LOVELACE");
        assertThat(commArea.getAccountId()).isEqualTo("00000000123");
        assertThat(commArea.getAccountStatus()).isEqualTo("Y");
        assertThat(commArea.getCardNumber()).isEqualTo("4111111111111111");
        assertThat(commArea.getLastMap()).isEqualTo("COMEN1A");
        assertThat(commArea.getLastMapset()).isEqualTo("COMEN01");
    }
}
