package com.vsergeychik.carddemo.testsupport;

import com.vsergeychik.carddemo.common.AbendException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The throwaway controller {@code WebConfigTest} drives its error-handling advice over.
 */
@RestController
public final class ScreenFixtureController {
    public static final String BASE = "/webconfig-fixture";

    public static final String WITHHELD_PROGRAM = "CBACT04C";

    public static final String WITHHELD_REASON = "ERROR OPENING ACCTFILE";

    @GetMapping(BASE + "/abend/{returnCode}")
    public String abendWithParameters(@PathVariable("returnCode") final int returnCode) {
        throw AbendException.standard(WITHHELD_PROGRAM, returnCode, WITHHELD_REASON);
    }

    /**
     * Raises an abend carrying neither {@code ABCODE} nor {@code TIMING}, which is the shape of the
     * {@code CBSTM03A} site at {@code app/cbl/CBSTM03A.CBL:923} - it sets neither argument.
     *
     * @param returnCode the {@code RETURN-CODE} the abending paragraph had set
     * @return never returns; the abend always propagates
     */
    @GetMapping(BASE + "/abend-bare/{returnCode}")
    public String abendWithoutParameters(@PathVariable("returnCode") final int returnCode) {
        throw AbendException.withoutAbendParameters(WITHHELD_PROGRAM, returnCode, WITHHELD_REASON);
    }

    /**
     * Raises the failure a width or shape guard produces when a value does not fit its {@code PICTURE}
     * clause.
     *
     * @return never returns
     */
    @GetMapping(BASE + "/rejected-value")
    public String rejectedValue() {
        throw new IllegalArgumentException(
                "CARD-NUM is PIC X(16) but '4111111111111111X' is 17 characters");
    }

    @GetMapping(BASE + "/state-fault")
    public String stateFault() {
        throw new IllegalStateException(
                "carddemo.datasets.acctdat is unbound; AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS");
    }

    @GetMapping(BASE + "/dataset-failure")
    public String datasetFailure() {
        throw new DataAccessResourceFailureException(
                "could not obtain a connection for AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS");
    }

    @GetMapping(BASE + "/violation")
    public String violation() {
        throw new ConstraintViolationException("USERID exceeds PIC X(8)", Set.of());
    }

    @GetMapping(BASE + "/unclaimed")
    public String unclaimed() {
        throw new UnsupportedOperationException(
                "internal detail that must not reach a response body");
    }

    @GetMapping(BASE + "/accounts/{accountId}")
    public String typedPathVariable(@PathVariable("accountId") final long accountId) {
        return String.valueOf(accountId);
    }

    @PostMapping(BASE + "/signon")
    public String signOn(@Valid @RequestBody final SignOnFixturePayload payload) {
        return payload.USERID();
    }

    @PostMapping(BASE + "/screen")
    public String screen(@RequestBody final ScreenFixturePayload payload) {
        return payload.TRNNAME();
    }

    public record SignOnFixturePayload(
            @Size(max = 8) String USERID,
            @Size(max = 8) String PASSWD) {
    }

    /**
     * A screen payload in the shape every request DTO in this module uses: character fields named for their
     * {@code DFHMDF} field, and one monetary field held as a {@code BigDecimal}.
     *
     * @param TRNNAME the transaction identifier, echoed on success
     * @param CURDATE the current date as the screen header carries it
     * @param PGMNAME the program name the screen was painted by
     * @param ERRMSG the error message line
     * @param ACCTSID the account identifier as typed
     * @param TAMT001 a monetary amount, so scale handling is exercised on the way in
     */
    public record ScreenFixturePayload(
            String TRNNAME,
            String CURDATE,
            String PGMNAME,
            String ERRMSG,
            String ACCTSID,
            BigDecimal TAMT001) {
    }
}
