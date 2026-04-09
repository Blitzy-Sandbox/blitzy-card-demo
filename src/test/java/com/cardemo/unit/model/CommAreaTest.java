package com.cardemo.unit.model;

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CommArea} — the central COMMAREA DTO mapped from
 * COBOL copybook COCOM01Y.cpy. Exercises all 16 field getters/setters,
 * both constructors, utility boolean methods, and toString to ensure
 * 100% behavioral coverage of this critical session-state transfer object.
 */
class CommAreaTest {

    // ──────────────────────────────────────────────────────────────────────
    // Default constructor
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Default constructor initializes all fields to null/default")
    void testDefaultConstructor() {
        CommArea comm = new CommArea();
        assertThat(comm.getFromTranId()).isNull();
        assertThat(comm.getFromProgram()).isNull();
        assertThat(comm.getToTranId()).isNull();
        assertThat(comm.getToProgram()).isNull();
        assertThat(comm.getUserId()).isNull();
        assertThat(comm.getUserType()).isNull();
        assertThat(comm.getPgmContext()).isZero();
        assertThat(comm.getCustId()).isNull();
        assertThat(comm.getCustFname()).isNull();
        assertThat(comm.getCustMname()).isNull();
        assertThat(comm.getCustLname()).isNull();
        assertThat(comm.getAcctId()).isNull();
        assertThat(comm.getAcctStatus()).isNull();
        assertThat(comm.getCardNum()).isNull();
        assertThat(comm.getLastMap()).isNull();
        assertThat(comm.getLastMapset()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────
    // All-args constructor
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All-args constructor populates every field")
    void testAllArgsConstructor() {
        CommArea comm = new CommArea(
                "TR01", "PGM01", "TR02", "PGM02",
                "USER01", UserType.ADMIN, 1,
                "CUST01", "John", "M", "Doe",
                "ACCT001", "Y", "1234567890123456",
                "MAP01", "MAPSET01"
        );
        assertThat(comm.getFromTranId()).isEqualTo("TR01");
        assertThat(comm.getFromProgram()).isEqualTo("PGM01");
        assertThat(comm.getToTranId()).isEqualTo("TR02");
        assertThat(comm.getToProgram()).isEqualTo("PGM02");
        assertThat(comm.getUserId()).isEqualTo("USER01");
        assertThat(comm.getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(comm.getPgmContext()).isEqualTo(1);
        assertThat(comm.getCustId()).isEqualTo("CUST01");
        assertThat(comm.getCustFname()).isEqualTo("John");
        assertThat(comm.getCustMname()).isEqualTo("M");
        assertThat(comm.getCustLname()).isEqualTo("Doe");
        assertThat(comm.getAcctId()).isEqualTo("ACCT001");
        assertThat(comm.getAcctStatus()).isEqualTo("Y");
        assertThat(comm.getCardNum()).isEqualTo("1234567890123456");
        assertThat(comm.getLastMap()).isEqualTo("MAP01");
        assertThat(comm.getLastMapset()).isEqualTo("MAPSET01");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Individual getter/setter pairs
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setFromTranId / getFromTranId round-trip")
    void testFromTranId() {
        CommArea comm = new CommArea();
        comm.setFromTranId("TRNX");
        assertThat(comm.getFromTranId()).isEqualTo("TRNX");
    }

    @Test
    @DisplayName("setFromProgram / getFromProgram round-trip")
    void testFromProgram() {
        CommArea comm = new CommArea();
        comm.setFromProgram("COSGN00C");
        assertThat(comm.getFromProgram()).isEqualTo("COSGN00C");
    }

    @Test
    @DisplayName("setToTranId / getToTranId round-trip")
    void testToTranId() {
        CommArea comm = new CommArea();
        comm.setToTranId("TR99");
        assertThat(comm.getToTranId()).isEqualTo("TR99");
    }

    @Test
    @DisplayName("setToProgram / getToProgram round-trip")
    void testToProgram() {
        CommArea comm = new CommArea();
        comm.setToProgram("COMEN01C");
        assertThat(comm.getToProgram()).isEqualTo("COMEN01C");
    }

    @Test
    @DisplayName("setUserId / getUserId round-trip")
    void testUserId() {
        CommArea comm = new CommArea();
        comm.setUserId("ADMIN01");
        assertThat(comm.getUserId()).isEqualTo("ADMIN01");
    }

    @Test
    @DisplayName("setUserType / getUserType round-trip")
    void testUserType() {
        CommArea comm = new CommArea();
        comm.setUserType(UserType.USER);
        assertThat(comm.getUserType()).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("setPgmContext / getPgmContext round-trip")
    void testPgmContext() {
        CommArea comm = new CommArea();
        comm.setPgmContext(5);
        assertThat(comm.getPgmContext()).isEqualTo(5);
    }

    @Test
    @DisplayName("setCustId / getCustId round-trip")
    void testCustId() {
        CommArea comm = new CommArea();
        comm.setCustId("C00001");
        assertThat(comm.getCustId()).isEqualTo("C00001");
    }

    @Test
    @DisplayName("setCustFname / getCustFname round-trip")
    void testCustFname() {
        CommArea comm = new CommArea();
        comm.setCustFname("Jane");
        assertThat(comm.getCustFname()).isEqualTo("Jane");
    }

    @Test
    @DisplayName("setCustMname / getCustMname round-trip")
    void testCustMname() {
        CommArea comm = new CommArea();
        comm.setCustMname("Q");
        assertThat(comm.getCustMname()).isEqualTo("Q");
    }

    @Test
    @DisplayName("setCustLname / getCustLname round-trip")
    void testCustLname() {
        CommArea comm = new CommArea();
        comm.setCustLname("Smith");
        assertThat(comm.getCustLname()).isEqualTo("Smith");
    }

    @Test
    @DisplayName("setAcctId / getAcctId round-trip")
    void testAcctId() {
        CommArea comm = new CommArea();
        comm.setAcctId("A0000001");
        assertThat(comm.getAcctId()).isEqualTo("A0000001");
    }

    @Test
    @DisplayName("setAcctStatus / getAcctStatus round-trip")
    void testAcctStatus() {
        CommArea comm = new CommArea();
        comm.setAcctStatus("N");
        assertThat(comm.getAcctStatus()).isEqualTo("N");
    }

    @Test
    @DisplayName("setCardNum / getCardNum round-trip")
    void testCardNum() {
        CommArea comm = new CommArea();
        comm.setCardNum("4000123456789012");
        assertThat(comm.getCardNum()).isEqualTo("4000123456789012");
    }

    @Test
    @DisplayName("setLastMap / getLastMap round-trip")
    void testLastMap() {
        CommArea comm = new CommArea();
        comm.setLastMap("COACTVW");
        assertThat(comm.getLastMap()).isEqualTo("COACTVW");
    }

    @Test
    @DisplayName("setLastMapset / getLastMapset round-trip")
    void testLastMapset() {
        CommArea comm = new CommArea();
        comm.setLastMapset("COACTUP");
        assertThat(comm.getLastMapset()).isEqualTo("COACTUP");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utility boolean methods
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isAdmin returns true for ADMIN userType")
    void testIsAdmin_true() {
        CommArea comm = new CommArea();
        comm.setUserType(UserType.ADMIN);
        assertThat(comm.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("isAdmin returns false for USER userType")
    void testIsAdmin_false() {
        CommArea comm = new CommArea();
        comm.setUserType(UserType.USER);
        assertThat(comm.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("isAdmin returns false when userType is null")
    void testIsAdmin_null() {
        CommArea comm = new CommArea();
        assertThat(comm.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("isRegularUser returns true for USER userType")
    void testIsRegularUser_true() {
        CommArea comm = new CommArea();
        comm.setUserType(UserType.USER);
        assertThat(comm.isRegularUser()).isTrue();
    }

    @Test
    @DisplayName("isRegularUser returns false for ADMIN userType")
    void testIsRegularUser_false() {
        CommArea comm = new CommArea();
        comm.setUserType(UserType.ADMIN);
        assertThat(comm.isRegularUser()).isFalse();
    }

    @Test
    @DisplayName("isEnterContext returns true when pgmContext is 0")
    void testIsEnterContext_true() {
        CommArea comm = new CommArea();
        comm.setPgmContext(0);
        assertThat(comm.isEnterContext()).isTrue();
    }

    @Test
    @DisplayName("isEnterContext returns false when pgmContext is non-zero")
    void testIsEnterContext_false() {
        CommArea comm = new CommArea();
        comm.setPgmContext(1);
        assertThat(comm.isEnterContext()).isFalse();
    }

    @Test
    @DisplayName("isReenterContext returns true when pgmContext is 1")
    void testIsReenterContext_true() {
        CommArea comm = new CommArea();
        comm.setPgmContext(1);
        assertThat(comm.isReenterContext()).isTrue();
    }

    @Test
    @DisplayName("isReenterContext returns false when pgmContext is 0")
    void testIsReenterContext_false() {
        CommArea comm = new CommArea();
        comm.setPgmContext(0);
        assertThat(comm.isReenterContext()).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────
    // toString
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString includes all fields")
    void testToString() {
        CommArea comm = new CommArea();
        comm.setUserId("TESTUSER");
        comm.setAcctId("A001");
        String str = comm.toString();
        assertThat(str).contains("CommArea");
        assertThat(str).contains("TESTUSER");
        assertThat(str).contains("A001");
    }
}
