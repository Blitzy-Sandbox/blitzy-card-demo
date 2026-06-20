package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.dto.CommArea;
import com.carddemo.model.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommArea}, the stateless Java equivalent of the COBOL
 * {@code CARDDEMO-COMMAREA} (copybook {@code COCOM01Y}). Verifies the JavaBean
 * accessor contract for every group field and the program-context constants.
 */
class CommAreaTest {

    @Test
    @DisplayName("program-context constants mirror COBOL CDEMO-PGM-ENTER/REENTER")
    void programContextConstants() {
        assertThat(CommArea.PGM_ENTER).isZero();
        assertThat(CommArea.PGM_REENTER).isEqualTo(1);
    }

    @Test
    @DisplayName("all general/customer/account/card/more-info fields round-trip")
    void allFieldsRoundTrip() {
        CommArea c = new CommArea();

        // CDEMO-GENERAL-INFO
        c.setFromTranId("CT00");
        c.setFromProgram("COSGN00C");
        c.setToTranId("CM00");
        c.setToProgram("COMEN01C");
        c.setUserId("USER0001");
        c.setUserType(UserType.ADMIN);
        c.setPgmContext(CommArea.PGM_REENTER);

        // CDEMO-CUSTOMER-INFO
        c.setCustomerId("000000001");
        c.setCustomerFirstName("JANE");
        c.setCustomerMiddleName("Q");
        c.setCustomerLastName("DOE");

        // CDEMO-ACCOUNT-INFO
        c.setAccountId("00000000001");
        c.setAccountStatus("Y");

        // CDEMO-CARD-INFO
        c.setCardNumber("4111111111111111");

        // CDEMO-MORE-INFO
        c.setLastMap("COMEN1A");
        c.setLastMapset("COMEN01");

        assertThat(c.getFromTranId()).isEqualTo("CT00");
        assertThat(c.getFromProgram()).isEqualTo("COSGN00C");
        assertThat(c.getToTranId()).isEqualTo("CM00");
        assertThat(c.getToProgram()).isEqualTo("COMEN01C");
        assertThat(c.getUserId()).isEqualTo("USER0001");
        assertThat(c.getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(c.getPgmContext()).isEqualTo(CommArea.PGM_REENTER);

        assertThat(c.getCustomerId()).isEqualTo("000000001");
        assertThat(c.getCustomerFirstName()).isEqualTo("JANE");
        assertThat(c.getCustomerMiddleName()).isEqualTo("Q");
        assertThat(c.getCustomerLastName()).isEqualTo("DOE");

        assertThat(c.getAccountId()).isEqualTo("00000000001");
        assertThat(c.getAccountStatus()).isEqualTo("Y");

        assertThat(c.getCardNumber()).isEqualTo("4111111111111111");

        assertThat(c.getLastMap()).isEqualTo("COMEN1A");
        assertThat(c.getLastMapset()).isEqualTo("COMEN01");
    }

    @Test
    @DisplayName("a freshly constructed CommArea holds no conversational state")
    void freshInstanceIsEmpty() {
        CommArea c = new CommArea();
        assertThat(c.getUserId()).isNull();
        assertThat(c.getUserType()).isNull();
        assertThat(c.getAccountId()).isNull();
        assertThat(c.getCardNumber()).isNull();
        assertThat(c.getPgmContext()).isEqualTo(CommArea.PGM_ENTER);
    }
}
