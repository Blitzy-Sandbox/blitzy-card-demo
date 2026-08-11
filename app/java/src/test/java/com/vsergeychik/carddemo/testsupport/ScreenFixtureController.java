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
 *
 * <p>It exists so that every {@code @ExceptionHandler} in {@code WebConfig.CobolErrorHandler} can be
 * reached through the real dispatcher without importing any of the seventeen production controllers -
 * each of which owns its own test in its own domain package, and each of which would drag a service
 * and a repository into a configuration test. Every endpoint does exactly one thing: raise one
 * failure, or accept one payload.
 *
 * <h2>Why it lives here rather than nested inside the suite that uses it</h2>
 * <p>The stereotype is not optional and the package is not incidental.
 * {@code MockMvcBuilders.standaloneSetup} registers the handler methods of a
 * {@code @Controller}-annotated instance, and {@code @RestController} is also what makes each
 * {@code String} return a response body instead of a view name - drop it and every body assertion in
 * that suite fails. But {@code @RestController} carries {@code @Controller}, which is meta-annotated
 * {@code @Component}, so the class is a component-scan candidate wherever it sits. Nested in
 * {@code com.vsergeychik.carddemo.config} it sat inside one of {@code CardDemoApplication}'s eleven
 * scanned packages, and any context refreshed with {@code target/test-classes} on the classpath -
 * which is how this module is started locally - registered it as an eighteenth
 * {@code @RestController} bean publishing ten {@code /webconfig-fixture/**} routes alongside the
 * seventeen API routes the deployed artifact publishes. Several of those fixture routes abend by
 * design and answer {@code 500}. Moving the class into this deliberately unscanned package keeps the
 * stereotype the test needs and takes the class out of component scope; see this package's
 * documentation for the whole rule.
 *
 * <p>Public, and its handler methods with it, because the suite it serves is in another package and
 * the dispatcher invokes the methods reflectively.
 */
@RestController
public final class ScreenFixtureController {

    /** The path prefix every fixture endpoint shares. */
    public static final String BASE = "/webconfig-fixture";

    /**
     * A program name that must never appear in a response body.
     *
     * <p>Published so the suite asserting the withholding and the fixture raising the failure quote
     * one value; two copies could drift and the assertion would then prove nothing.
     */
    public static final String WITHHELD_PROGRAM = "CBACT04C";

    /** A reason string that must never appear in a response body. */
    public static final String WITHHELD_REASON = "ERROR OPENING ACCTFILE";

    /**
     * Raises an abend carrying both {@code ABCODE} and {@code TIMING}, as the eight standard
     * {@code CALL 'CEE3ABD'} sites do.
     *
     * @param returnCode the {@code RETURN-CODE} the abending paragraph had set
     * @return never returns; the abend always propagates
     */
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
     * Raises the failure a width or shape guard produces when a value does not fit its
     * {@code PICTURE} clause.
     *
     * @return never returns
     */
    @GetMapping(BASE + "/rejected-value")
    public String rejectedValue() {
        throw new IllegalArgumentException(
                "CARD-NUM is PIC X(16) but '4111111111111111X' is 17 characters");
    }

    /**
     * Raises the failure a missing or contradictory dataset binding produces.
     *
     * @return never returns
     */
    @GetMapping(BASE + "/state-fault")
    public String stateFault() {
        throw new IllegalStateException(
                "carddemo.datasets.acctdat is unbound; AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS");
    }

    /**
     * Raises a failure reaching a dataset, which is distinct from a record simply being absent.
     *
     * @return never returns
     */
    @GetMapping(BASE + "/dataset-failure")
    public String datasetFailure() {
        throw new DataAccessResourceFailureException(
                "could not obtain a connection for AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS");
    }

    /**
     * Raises a constraint violation from outside request-body binding, as a validated path variable
     * or service argument would.
     *
     * @return never returns
     */
    @GetMapping(BASE + "/violation")
    public String violation() {
        throw new ConstraintViolationException("USERID exceeds PIC X(8)", Set.of());
    }

    /**
     * Raises a failure no handler declares, so the status-preserving catch-all claims it.
     *
     * @return never returns
     */
    @GetMapping(BASE + "/unclaimed")
    public String unclaimed() {
        throw new UnsupportedOperationException(
                "internal detail that must not reach a response body");
    }

    /**
     * Accepts a numeric path variable, so a non-numeric one produces a conversion failure.
     *
     * @param accountId the eleven-digit account identifier
     * @return the identifier, echoed, when conversion succeeds
     */
    @GetMapping(BASE + "/accounts/{accountId}")
    public String typedPathVariable(@PathVariable("accountId") final long accountId) {
        return String.valueOf(accountId);
    }

    /**
     * Accepts a validated request body, so a width breach produces a validation failure.
     *
     * @param payload the sign-on payload
     * @return the accepted user identifier
     */
    @PostMapping(BASE + "/signon")
    public String signOn(@Valid @RequestBody final SignOnFixturePayload payload) {
        return payload.USERID();
    }

    /**
     * Accepts a screen payload, so a malformed body produces a parse failure.
     *
     * @param payload the screen payload
     * @return the accepted transaction identifier
     */
    @PostMapping(BASE + "/screen")
    public String screen(@RequestBody final ScreenFixturePayload payload) {
        return payload.TRNNAME();
    }

    /**
     * The smallest payload that can carry a width-constrained screen field.
     *
     * <p>Both widths are the ones the symbolic map declares. {@code app/cpy-bms/COSGN00.CPY:38} is
     * {@code USERIDI PIC X(8)}, and the {@code USERID} field of {@code app/bms/COSGN00.bms} is
     * {@code LENGTH=8} - a validation length always traces to an {@code xxxI} picture clause.
     *
     * @param USERID the sign-on user identifier, at most eight characters
     * @param PASSWD the sign-on password, at most eight characters. It stays plaintext, exactly as
     *               {@code COSGN00C} compares {@code SEC-USR-PWD PIC X(08)} against {@code USRSEC};
     *               hashing it would be a behaviour change and would need a framework this migration
     *               excludes (practice B6, gate G41)
     */
    public record SignOnFixturePayload(
            @Size(max = 8) String USERID,
            @Size(max = 8) String PASSWD) {
    }

    /**
     * A screen payload in the shape every request DTO in this module uses: character fields named for
     * their {@code DFHMDF} field, and one monetary field held as a {@code BigDecimal}.
     *
     * <p>It exists so a body can be sent <em>malformed</em> and the parse failure observed. Field
     * names match the symbolic-map items rather than Java convention, which is the module's rule for
     * every payload: a payload field traces to a screen field or it does not belong there.
     *
     * @param TRNNAME the transaction identifier, echoed on success
     * @param CURDATE the current date as the screen header carries it
     * @param PGMNAME the program name the screen was painted by
     * @param ERRMSG  the error message line
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
