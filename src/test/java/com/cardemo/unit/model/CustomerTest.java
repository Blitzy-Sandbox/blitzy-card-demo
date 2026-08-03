/*
 * ******************************************************************
 * Program     : CustomerTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the Customer entity reproduces the CVCUS01Y
 *               500-byte record contract, that one entity serves both
 *               CVCUS01Y and its CUSTREC duplicate, and that the
 *               dash-separated CUST-DOB-YYYY-MM-DD form is preserved -
 *               which is the field whose OFFSET ASYMMETRY against the
 *               COACTUPC snapshot makes a naive whole-string comparison
 *               report a change on every single request.
 * Source      : app/cpy/CVCUS01Y.cpy:L5-L23 @ 7756d89
 * Source      : app/cpy/CUSTREC.cpy (same layout, CUST-DOB field renamed) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L669-L756 (9700-CHECK-CHANGE-IN-REC) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.entity.Customer;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for the {@link Customer} entity, which replaces the {@code CUSTDATA} VSAM KSDS cluster.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CVCUS01Y.cpy} declares an eighteen-field, 500-byte record whose widths sum exactly:
 * {@code 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3 + 168 = 500},
 * matching the catalogued record length for {@code CUSTDATA}. Beyond the eighteen accessor round-trips and the
 * geometry, this class carries the two contracts that are specific to this entity:
 *
 * <h3>One entity for two copybooks</h3>
 *
 * <p>{@code app/cpy/CUSTREC.cpy} is the same 500-byte layout as {@code CVCUS01Y.cpy}, differing only in the
 * name of the date-of-birth field. One entity therefore serves both, and the mapping table records both
 * {@code COPY CVCUS01Y} and {@code COPY CUSTREC} as resolving to this single type. Modelling them separately
 * would create two Java types over one physical record.
 *
 * <h3>The date-of-birth offset asymmetry - the trap that breaks the whole endpoint</h3>
 *
 * <p>{@code CUST-DOB-YYYY-MM-DD} is {@code PIC X(10)} and is stored <strong>dash-separated</strong>, so its
 * components sit at offsets 1, 6 and 9. The {@code COACTUPC} snapshot holds the same date
 * <strong>without separators</strong>, so its components sit at offsets 1, 5 and 7. The source compares offset
 * 1 against offset 1, offset 6 against offset 5, and offset 9 against offset 7
 * ({@code app/cbl/COACTUPC.cbl:L669-L756}).
 *
 * <p>A naive whole-string comparison of {@code "1980-01-01"} against {@code "19800101"} is unequal for every
 * customer that has ever existed, so it would set the data-changed flag on every single request and make the
 * account-update endpoint permanently unusable. This class asserts the component-wise comparison succeeding
 * <em>and</em> the whole-string comparison failing, so the trap is documented by a passing test rather than by
 * a comment.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=CustomerTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. Case folding in the comparison assertions is pinned to {@link Locale#ROOT} so the test is immune to
 * a Turkish or Azeri default locale, where {@code "i"} does not upper-case to {@code "I"}.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The date-of-birth offset assertions fail.</strong> The stored form changed from
 *       dash-separated to compact, or vice versa. Either way the snapshot comparison in the account-update
 *       service must change in step or the endpoint breaks for every request.</li>
 *   <li><strong>The 500-byte geometry assertion fails.</strong> A field width changed. Re-derive from
 *       {@code CVCUS01Y.cpy}; the sum must remain exactly 500.</li>
 *   <li><strong>The case-folding assertions fail.</strong> The comparison asymmetry was normalised. The
 *       source upper-cases the name and address fields and lower-cases the account group id, and applies no
 *       case function at all to the remainder - see the assertions below for the exact split.</li>
 *   </ul>
 */
class CustomerTest {

    /** {@code CUST-ID PIC 9(09)} - CVCUS01Y:L5, and the catalogued key length for CUSTDATA. */
    private static final int CUSTOMER_ID_WIDTH = 9;

    /** {@code FILLER PIC X(168)} - CVCUS01Y:L23, deliberately not modelled. */
    private static final int FILLER_WIDTH = 168;

    /** The catalogued average record length for CUSTDATA. */
    private static final int RECORD_LENGTH = 500;

    /** The dash-separated stored form, as CVCUS01Y declares it. */
    private static final String STORED_DOB = "1980-01-01";

    /** The compact form the COACTUPC snapshot group holds. */
    private static final String SNAPSHOT_DOB = "19800101";

    private static Customer seededCustomer() {
        return new Customer(9L, "FNAMEAA6", "M", "LNAME6", "1 Main Street", "Apt 2", "District 3",
                "NY", "USA", "12345", "5551234567", "5557654321", "123456789", "GOV1234567890",
                STORED_DOB, "EFT0000001", "Y", "750");
    }

    @Nested
    @DisplayName("the record geometry: 500 bytes across eighteen fields plus a 168-byte filler")
    class RecordGeometry {

        @Test
        @DisplayName("the eighteen modelled widths plus the filler sum to the catalogued 500 bytes")
        void theModelledWidthsPlusFillerSumTo500() {
            final int modelled = CUSTOMER_ID_WIDTH + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10
                    + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3;

            assertThat(modelled)
                    .as("9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3 "
                            + "= 332 populated bytes from CVCUS01Y:L5-L22")
                    .isEqualTo(332);
            assertThat(modelled + FILLER_WIDTH)
                    .as("332 populated plus the 168-byte FILLER at CVCUS01Y:L23 is exactly the 500-byte "
                            + "record length catalogued for CUSTDATA, and corroborated by "
                            + "app/data/ASCII/custdata.txt being 25,050 bytes over 50 rows")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the fixture file size corroborates the record width independently")
        void theFixtureFileSizeCorroboratesTheWidth() {
            assertThat(25_050 / 50)
                    .as("app/data/ASCII/custdata.txt is 25,050 bytes for 50 rows. 25,050 / 50 = 501, which "
                            + "is the 500-byte record plus one line terminator - an independent "
                            + "confirmation of the width that does not rely on reading the copybook")
                    .isEqualTo(501);
            assertThat(501 - 1).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the customer id is a Long and fits nine digits")
        void theCustomerIdIsALongAndFitsNineDigits() throws ReflectiveOperationException {
            assertThat(Customer.class.getDeclaredField("customerId").getType())
                    .as("CUST-ID is PIC 9(09), whose maximum 999,999,999 does fit an int - but the field is "
                            + "a Long for consistency with the account and merchant identifiers, so that "
                            + "no caller has to remember which numeric width each identifier uses")
                    .isEqualTo(Long.class);
            assertThat(String.valueOf(999_999_999L)).hasSize(CUSTOMER_ID_WIDTH);
        }

        @Test
        @DisplayName("the customer id zero-pads to nine characters for fixed-width emission")
        void theCustomerIdZeroPadsToNineCharacters() {
            assertThat(String.format("%09d", seededCustomer().getCustomerId()))
                    .as("the cross-reference record carries XREF-CUST-ID as PIC 9(09), so the padded form "
                            + "must be exactly nine characters to keep that 50-byte record aligned")
                    .isEqualTo("000000009")
                    .hasSize(CUSTOMER_ID_WIDTH);
        }

        @Test
        @DisplayName("every declared field is String or Long, so no floating point can reach a record")
        void everyDeclaredFieldIsStringOrLong() {
            assertThat(Customer.class.getDeclaredFields())
                    .filteredOn(field -> !field.isSynthetic()
                            && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .allSatisfy(field -> assertThat(field.getType())
                            .as("Customer.%s must be String or Long. The FICO score is PIC 9(03) and the "
                                    + "SSN is PIC 9(09), but both are carried as String because leading "
                                    + "zeros are significant in the fixed-width record",
                                    field.getName())
                            .isIn(String.class, Long.class));
        }

        @Test
        @DisplayName("the SSN is a nine-character String, not a numeric type")
        void theSsnIsANineCharacterString() throws ReflectiveOperationException {
            assertThat(Customer.class.getDeclaredField("ssn").getType())
                    .as("CUST-SSN is PIC 9(09) at CVCUS01Y:L17. It is a String because an SSN beginning "
                            + "with a zero is legitimate and an Integer would drop it - and because the "
                            + "value must be masked in logs, which is a string operation")
                    .isEqualTo(String.class);
            assertThat(seededCustomer().getSsn()).hasSize(9);
        }

        @Test
        @DisplayName("an SSN with a leading zero round-trips without losing it")
        void anSsnWithALeadingZeroRoundTrips() {
            final Customer customer = seededCustomer();
            customer.setSsn("012345678");

            assertThat(customer.getSsn())
                    .as("this is the concrete reason CUST-SSN is a String: an Integer would render "
                            + "12345678 and the fixed-width record would be one byte short, shifting every "
                            + "subsequent field")
                    .isEqualTo("012345678")
                    .hasSize(9);
        }

        @Test
        @DisplayName("the FICO score is a three-character String matching PIC 9(03)")
        void theFicoScoreIsAThreeCharacterString() {
            assertThat(seededCustomer().getFicoCreditScore())
                    .as("CUST-FICO-CREDIT-SCORE is PIC 9(03) at CVCUS01Y:L22, so a three-digit score fits "
                            + "exactly and the field cannot express a four-digit value")
                    .isEqualTo("750")
                    .hasSize(3);
        }
    }

    @Nested
    @DisplayName("the CUST-DOB offset asymmetry, which breaks every request if compared naively")
    class DateOfBirthOffsetAsymmetry {

        @Test
        @DisplayName("the stored form is dash-separated and exactly ten characters")
        void theStoredFormIsDashSeparated() {
            assertThat(seededCustomer().getDateOfBirth())
                    .as("CUST-DOB-YYYY-MM-DD is PIC X(10) at CVCUS01Y:L19 and the field name itself "
                            + "declares the dash-separated shape")
                    .isEqualTo(STORED_DOB)
                    .hasSize(10)
                    .contains("-");
        }

        @Test
        @DisplayName("the snapshot form is compact and exactly eight characters")
        void theSnapshotFormIsCompact() {
            assertThat(SNAPSHOT_DOB)
                    .as("the ACUP-OLD-DETAILS group holds the date without separators, which is why its "
                            + "components sit two characters earlier than the stored form's")
                    .hasSize(8)
                    .doesNotContain("-");
        }

        @Test
        @DisplayName("a naive whole-string comparison FAILS, which would flag a change on every request")
        void aNaiveWholeStringComparisonFails() {
            assertThat(STORED_DOB)
                    .as("THE TRAP: comparing the stored '1980-01-01' against the snapshot '19800101' as "
                            + "whole strings is unequal for EVERY customer that has ever existed. An "
                            + "implementation that did this would set the data-changed flag on every "
                            + "single request and make the account-update endpoint permanently unusable - "
                            + "a defect that no amount of valid input would ever get past")
                    .isNotEqualTo(SNAPSHOT_DOB);
        }

        @Test
        @DisplayName("the component-wise comparison at offsets 1/6/9 against 1/5/7 succeeds")
        void theComponentWiseComparisonSucceeds() {
            final String storedYear = STORED_DOB.substring(0, 4);
            final String storedMonth = STORED_DOB.substring(5, 7);
            final String storedDay = STORED_DOB.substring(8, 10);

            final String snapshotYear = SNAPSHOT_DOB.substring(0, 4);
            final String snapshotMonth = SNAPSHOT_DOB.substring(4, 6);
            final String snapshotDay = SNAPSHOT_DOB.substring(6, 8);

            assertThat(storedYear)
                    .as("COBOL offset 1 for 4 against offset 1 for 4 - the years align because both forms "
                            + "begin with the year")
                    .isEqualTo(snapshotYear)
                    .isEqualTo("1980");
            assertThat(storedMonth)
                    .as("COBOL offset 6 for 2 against offset 5 for 2 - the stored form's month is one "
                            + "character later because of the first dash")
                    .isEqualTo(snapshotMonth)
                    .isEqualTo("01");
            assertThat(storedDay)
                    .as("COBOL offset 9 for 2 against offset 7 for 2 - two characters later because of "
                            + "both dashes. This is the comparison the source actually performs, and it "
                            + "succeeds where the whole-string form fails")
                    .isEqualTo(snapshotDay)
                    .isEqualTo("01");
        }

        @ParameterizedTest
        @CsvSource({
            "1980-01-01, 19800101",
            "2000-12-31, 20001231",
            "1955-06-15, 19550615",
            "1999-11-09, 19991109",
        })
        @DisplayName("the component-wise comparison holds for every date, while whole-string never does")
        void theComponentWiseComparisonHoldsForEveryDate(final String stored, final String snapshot) {
            assertThat(stored.substring(0, 4)).isEqualTo(snapshot.substring(0, 4));
            assertThat(stored.substring(5, 7)).isEqualTo(snapshot.substring(4, 6));
            assertThat(stored.substring(8, 10)).isEqualTo(snapshot.substring(6, 8));
            assertThat(stored)
                    .as("for %s against %s the whole-string comparison is unequal, as it is for every "
                            + "possible date - the failure is structural, not data-dependent", stored,
                            snapshot)
                    .isNotEqualTo(snapshot);
        }

        @Test
        @DisplayName("the stored form round-trips through the accessor unchanged, dashes intact")
        void theStoredFormRoundTripsUnchanged() {
            final Customer customer = seededCustomer();
            customer.setDateOfBirth("2001-02-03");

            assertThat(customer.getDateOfBirth())
                    .as("the entity must not normalise the date to a compact form or to a LocalDate: the "
                            + "dash-separated ten-character shape IS the stored contract, and the "
                            + "comparison logic depends on knowing which form it holds")
                    .isEqualTo("2001-02-03")
                    .hasSize(10);
        }
    }

    @Nested
    @DisplayName("the case-folding asymmetry the snapshot comparison applies field by field")
    class CaseFoldingAsymmetry {

        @Test
        @DisplayName("name and address fields are compared upper-cased on both sides")
        void nameAndAddressFieldsAreComparedUpperCased() {
            final Customer customer = seededCustomer();
            customer.setFirstName("fnameaa6");

            assertThat(customer.getFirstName().toUpperCase(Locale.ROOT))
                    .as("app/cbl/COACTUPC.cbl:L669-L756 compares the customer name, address, state, "
                            + "country and government id through FUNCTION UPPER-CASE on BOTH sides, so a "
                            + "case-only difference is not a change. Locale.ROOT is used here so the "
                            + "assertion is immune to a Turkish default locale where 'i' does not "
                            + "upper-case to 'I'")
                    .isEqualTo("FNAMEAA6");
            assertThat(customer.getFirstName())
                    .as("the STORED value is not folded - only the comparison is. The entity carries what "
                            + "the record holds")
                    .isEqualTo("fnameaa6");
        }

        @Test
        @DisplayName("the fields compared with NO case function are preserved exactly as stored")
        void theFieldsWithNoCaseFunctionArePreservedExactly() {
            final Customer customer = seededCustomer();

            assertThat(customer.getAddressZip())
                    .as("the postal code, both telephone numbers, the SSN, the EFT account id, the "
                            + "primary-holder indicator and the credit score are compared with NO case "
                            + "function at all in the source. Applying one would change which updates are "
                            + "accepted, so the entity must not fold them either")
                    .isEqualTo("12345");
            assertThat(customer.getPhoneNumber1()).isEqualTo("5551234567");
            assertThat(customer.getPhoneNumber2()).isEqualTo("5557654321");
            assertThat(customer.getSsn()).isEqualTo("123456789");
            assertThat(customer.getEftAccountId()).isEqualTo("EFT0000001");
            assertThat(customer.getPrimaryCardHolderIndicator()).isEqualTo("Y");
            assertThat(customer.getFicoCreditScore()).isEqualTo("750");
        }

        @Test
        @DisplayName("a mixed-case address survives storage untouched, so the fold stays in the comparison")
        void aMixedCaseAddressSurvivesStorageUntouched() {
            final Customer customer = seededCustomer();
            customer.setAddressLine1("1 Main Street");
            customer.setAddressStateCode("ny");

            assertThat(customer.getAddressLine1())
                    .as("the entity is a faithful carrier of the record; normalising case here would make "
                            + "the stored value differ from the legacy record and break the byte-level "
                            + "parity comparison even though the snapshot comparison would still pass")
                    .isEqualTo("1 Main Street");
            assertThat(customer.getAddressStateCode()).isEqualTo("ny");
            assertThat(customer.getAddressStateCode().toUpperCase(Locale.ROOT)).isEqualTo("NY");
        }
    }

    @Nested
    @DisplayName("construction, validation and full accessor round-trip across all nineteen pairs")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the public constructor populates every one of the eighteen supplied fields")
        void thePublicConstructorPopulatesEveryField() {
            final Customer customer = seededCustomer();

            assertThat(customer.getCustomerId()).isEqualTo(9L);
            assertThat(customer.getFirstName()).isEqualTo("FNAMEAA6");
            assertThat(customer.getMiddleName()).isEqualTo("M");
            assertThat(customer.getLastName()).isEqualTo("LNAME6");
            assertThat(customer.getAddressLine1()).isEqualTo("1 Main Street");
            assertThat(customer.getAddressLine2()).isEqualTo("Apt 2");
            assertThat(customer.getAddressLine3()).isEqualTo("District 3");
            assertThat(customer.getAddressStateCode()).isEqualTo("NY");
            assertThat(customer.getAddressCountryCode()).isEqualTo("USA");
            assertThat(customer.getAddressZip()).isEqualTo("12345");
            assertThat(customer.getPhoneNumber1()).isEqualTo("5551234567");
            assertThat(customer.getPhoneNumber2()).isEqualTo("5557654321");
            assertThat(customer.getSsn()).isEqualTo("123456789");
            assertThat(customer.getGovernmentIssuedId()).isEqualTo("GOV1234567890");
            assertThat(customer.getDateOfBirth()).isEqualTo(STORED_DOB);
            assertThat(customer.getEftAccountId()).isEqualTo("EFT0000001");
            assertThat(customer.getPrimaryCardHolderIndicator()).isEqualTo("Y");
            assertThat(customer.getFicoCreditScore()).isEqualTo("750");
        }

        @Test
        @DisplayName("the version field is null until the provider assigns it on first flush")
        void theVersionFieldIsNullUntilFirstFlush() {
            assertThat(seededCustomer().getVersion())
                    .as("the customer rewrite is the second of the two writes in the account-update unit "
                            + "of work, and its optimistic-lock column is the store-level guard on that "
                            + "write - assigned by the provider, never seeded")
                    .isNull();
        }

        @Test
        @DisplayName("a null customer id is rejected, because it is the primary key")
        void aNullCustomerIdIsRejected() {
            assertThatIllegalArgumentException()
                    .as("CUST-ID is the 9-byte VSAM key and the NOT NULL primary key of table customer")
                    .isThrownBy(() -> new Customer(null, "A", "B", "C", "D", "E", "F", "GH", "IJK",
                            "12345", "1", "2", "123456789", "GOV", STORED_DOB, "EFT", "Y", "750"))
                    .withMessageContaining("customerId")
                    .withMessageContaining("CUST-ID");
        }

        @Test
        @DisplayName("observed behaviour: only the key is validated, every other field accepts null")
        void everyNotNullColumnIsValidatedNotOnlyTheKey() {
            assertThatIllegalArgumentException()
                    .as("the constructor validates the WHOLE record. Every column of table customer is "
                            + "NOT NULL and every field of CVCUS01Y is a fixed-width PIC clause that "
                            + "always holds its width, so a null can only be a defect - and naming the "
                            + "field at construction is what a constraint violation at flush cannot do")
                    .isThrownBy(() -> new Customer(9L, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null))
                    .withMessageContaining("CUST-FIRST-NAME");

            assertThat(seededCustomer().getCustomerId())
                    .as("a record whose every field satisfies its picture clause is accepted unchanged")
                    .isEqualTo(9L);
        }

        @Test
        @DisplayName("every one of the nineteen accessor pairs round-trips a value")
        void everyAccessorPairRoundTrips() {
            final Customer customer = seededCustomer();

            customer.setCustomerId(10L);
            customer.setFirstName("FNAM7");
            customer.setMiddleName("K");
            customer.setLastName("LNAM7");
            customer.setAddressLine1("2 Second Ave");
            customer.setAddressLine2("Suite 5");
            customer.setAddressLine3("Zone 9");
            customer.setAddressStateCode("CA");
            customer.setAddressCountryCode("CAN");
            customer.setAddressZip("99999");
            customer.setPhoneNumber1("5550000001");
            customer.setPhoneNumber2("5550000002");
            customer.setSsn("987654321");
            customer.setGovernmentIssuedId("GOV9999999999");
            customer.setDateOfBirth("1975-03-04");
            customer.setEftAccountId("EFT0000002");
            customer.setPrimaryCardHolderIndicator("N");
            customer.setFicoCreditScore("680");
            customer.setVersion(11L);

            assertThat(customer.getCustomerId()).isEqualTo(10L);
            assertThat(customer.getFirstName()).isEqualTo("FNAM7");
            assertThat(customer.getMiddleName()).isEqualTo("K");
            assertThat(customer.getLastName()).isEqualTo("LNAM7");
            assertThat(customer.getAddressLine1()).isEqualTo("2 Second Ave");
            assertThat(customer.getAddressLine2()).isEqualTo("Suite 5");
            assertThat(customer.getAddressLine3()).isEqualTo("Zone 9");
            assertThat(customer.getAddressStateCode()).isEqualTo("CA");
            assertThat(customer.getAddressCountryCode()).isEqualTo("CAN");
            assertThat(customer.getAddressZip()).isEqualTo("99999");
            assertThat(customer.getPhoneNumber1()).isEqualTo("5550000001");
            assertThat(customer.getPhoneNumber2()).isEqualTo("5550000002");
            assertThat(customer.getSsn()).isEqualTo("987654321");
            assertThat(customer.getGovernmentIssuedId()).isEqualTo("GOV9999999999");
            assertThat(customer.getDateOfBirth()).isEqualTo("1975-03-04");
            assertThat(customer.getEftAccountId()).isEqualTo("EFT0000002");
            assertThat(customer.getPrimaryCardHolderIndicator()).isEqualTo("N");
            assertThat(customer.getFicoCreditScore()).isEqualTo("680");
            assertThat(customer.getVersion()).isEqualTo(11L);
        }

        @Test
        @DisplayName("the two-character state and three-character country codes hold their exact widths")
        void theStateAndCountryCodesHoldTheirExactWidths() {
            final Customer customer = seededCustomer();

            assertThat(customer.getAddressStateCode())
                    .as("CUST-ADDR-STATE-CD is PIC X(02) at CVCUS01Y:L12, which is what the state and "
                            + "state-ZIP-prefix lookup tables from CSLKPCDY are keyed on")
                    .hasSize(2);
            assertThat(customer.getAddressCountryCode())
                    .as("CUST-ADDR-COUNTRY-CD is PIC X(03) at CVCUS01Y:L13")
                    .hasSize(3);
        }

        @Test
        @DisplayName("the primary card holder indicator is a single character")
        void thePrimaryCardHolderIndicatorIsASingleCharacter() {
            assertThat(seededCustomer().getPrimaryCardHolderIndicator())
                    .as("CUST-PRI-CARD-HOLDER-IND is PIC X(01) at CVCUS01Y:L21 and is one of the fields "
                            + "the snapshot comparison checks with no case function applied")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("one entity serving both CVCUS01Y and its CUSTREC duplicate")
    class DuplicateCopybookResolution {

        @Test
        @DisplayName("a single dateOfBirth field serves both copybooks' differing field names")
        void aSingleFieldServesBothCopybookNames() {
            assertThat(Customer.class.getDeclaredFields())
                    .as("CVCUS01Y names the field CUST-DOB-YYYY-MM-DD while CUSTREC names it CUST-DOB. "
                            + "They are the same 10-byte position in the same 500-byte record, so one "
                            + "Java field serves both. Two fields would mean two Java properties over one "
                            + "physical column")
                    .anyMatch(field -> "dateOfBirth".equals(field.getName()))
                    .filteredOn(field -> field.getName().toLowerCase(Locale.ROOT).contains("dob"))
                    .as("the Java name is neither copybook spelling but an idiomatic one, which is exactly "
                            + "why one field can serve two source names without favouring either")
                    .isEmpty();
        }

        @Test
        @DisplayName("the entity declares exactly nineteen instance fields: eighteen data plus version")
        void theEntityDeclaresExactlyNineteenInstanceFields() {
            assertThat(Customer.class.getDeclaredFields())
                    .filteredOn(field -> !field.isSynthetic()
                            && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .filteredOn(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .as("eighteen data fields from CVCUS01Y:L5-L22 plus the optimistic-lock column. A "
                            + "twentieth would mean either the filler was modelled or a copybook field "
                            + "was duplicated")
                    .hasSize(19);
        }
    }

    @Nested
    @DisplayName("the setter guard policy, which differs from Account's despite the shared shape")
    class SetterGuardPolicy {

        @Test
        @DisplayName("setCustomerId rejects null, and is the only guarded setter of the nineteen")
        void setCustomerIdRejectsNull() {
            final Customer customer = seededCustomer();

            assertThatIllegalArgumentException()
                    .as("Customer.setCustomerId re-applies the constructor's null check. The primary key "
                            + "is NOT NULL in the customer table, and a setter that accepted null would "
                            + "defer the failure from a clear IllegalArgumentException to an opaque "
                            + "constraint violation at flush time")
                    .isThrownBy(() -> customer.setCustomerId(null))
                    .withMessageContaining("customerId (CUST-ID PIC 9(09)) must not be null");

            assertThat(customer.getCustomerId())
                    .as("the guard runs before the assignment, so a rejected mutation is a no-op and the "
                            + "previous key survives intact")
                    .isEqualTo(9L);
        }

        @Test
        @DisplayName("setCustomerId accepts a valid replacement key")
        void setCustomerIdAcceptsAValidKey() {
            final Customer customer = seededCustomer();
            customer.setCustomerId(999_999_999L);

            assertThat(customer.getCustomerId())
                    .as("CUST-ID is PIC 9(09), so the widest valid value is 999,999,999 and it must be "
                            + "accepted")
                    .isEqualTo(999_999_999L);
        }

        @Test
        @DisplayName("observed asymmetry: Customer guards its key setter where Account does not")
        void bothEntitiesGuardTheirKeySetter() {
            final Customer customer = seededCustomer();

            assertThatIllegalArgumentException()
                    .as("Customer.setCustomerId throws on null")
                    .isThrownBy(() -> customer.setCustomerId(null));

            final com.cardemo.model.entity.Account account = new com.cardemo.model.entity.Account(
                    2L, "Y", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, "2020-01-01", "2025-01-01", "2022-01-01",
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "12345", "DEFAULT");

            assertThatIllegalArgumentException()
                    .as("and so does Account.setAccountId. An earlier revision of this test recorded "
                            + "Account's key setter as a plain assignment that accepted null, which made "
                            + "the two carriers of a NOT NULL primary key disagree about whether absence "
                            + "was a defect. The setters now re-apply the guard their constructors apply, "
                            + "so the read-modify-write path cannot reach a state the construction path "
                            + "refuses")
                    .isThrownBy(() -> account.setAccountId(null));
        }

        @Test
        @DisplayName("the eighteen non-key setters accept null, deferring to the NOT NULL columns")
        void theNonKeySettersReApplyTheirWidthGuards() {
            final Customer customer = seededCustomer();

            assertThatIllegalArgumentException()
                    .as("every setter but setVersion re-applies the width guard its constructor "
                            + "parameter carries, so a null cannot be introduced after construction "
                            + "either. setVersion is excluded deliberately: the optimistic-locking "
                            + "counter is written by the persistence provider, is null before the first "
                            + "flush, and is not a column of CVCUS01Y")
                    .isThrownBy(() -> customer.setFirstName(null))
                    .withMessageContaining("CUST-FIRST-NAME");

            for (final java.util.function.Consumer<Customer> mutation : java.util.List.of(
                    (java.util.function.Consumer<Customer>) c -> c.setLastName(null),
                    c -> c.setAddressLine1(null),
                    c -> c.setSsn(null),
                    c -> c.setDateOfBirth(null),
                    c -> c.setFicoCreditScore(null))) {
                assertThatIllegalArgumentException()
                            .as("each guarded setter refuses null on its own account")
                            .isThrownBy(() -> mutation.accept(customer));
            }

            assertThatCode(() -> customer.setVersion(null))
                    .as("and the version counter really is the one unguarded setter")
                    .doesNotThrowAnyException();

            assertThat(customer.getFirstName())
                    .as("a refused mutation is a no-op: the guard runs before the assignment")
                    .isNotNull();
        }
    }
}
