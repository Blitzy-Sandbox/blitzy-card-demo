package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.dto.StatementTransactionDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link StatementDocument}, the batch-internal aggregate that carries one
 * fully-assembled account/card statement from the statement processor to the writer. Legacy source
 * is referenced read-only at commit SHA {@code 27d6c6f}.
 *
 * <p>{@link StatementDocument} is the Java aggregate produced by the {@code 5000-CREATE-STATEMENT}
 * paragraph of {@code CBSTM03A.CBL}: for each card it joins the customer, account, card, and the
 * transaction lines returned by the file-access subprogram {@code CBSTM03B.CBL}
 * ({@code StatementFileService}), then feeds the plain-text ({@code STMT-FILE}) and HTML
 * ({@code HTML-FILE}) writers.</p>
 *
 * <p>This suite pins the record's data-holder contract:</p>
 * <ul>
 *   <li><strong>{@code cardXref} is required</strong> — the canonical constructor rejects a
 *       {@code null} cross-reference (it backs {@link StatementDocument#cardNumber()}).</li>
 *   <li><strong>Null lists coalesce to empty</strong> and every stored list is an
 *       <em>immutable defensive copy</em> ({@link List#copyOf}), so callers cannot mutate the
 *       document after construction and a mutation of the source list does not leak in.</li>
 *   <li><strong>{@link StatementDocument#cardNumber()} echoes {@code cardXref.getXrefCardNum()}.</strong></li>
 * </ul>
 *
 * <p>No Spring context, database, or network is used, so it is fast and compiles warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2), contributing to Gate&nbsp;8 coverage.</p>
 *
 * @see StatementDocument
 */
@DisplayName("StatementDocument — statement aggregate (CBSTM03A 5000-CREATE-STATEMENT)")
class StatementDocumentTest {

    private static final String CARD_NUMBER = "4111111111111111";

    /** A cross-reference carrying the 16-character card number the document keys off. */
    private static CardXref cardXref() {
        final CardXref xref = new CardXref();
        xref.setXrefCardNum(CARD_NUMBER);
        return xref;
    }

    /**
     * Builds a document with the given lists and non-null entity references.
     *
     * @param txnLines  the transaction lines (may be {@code null})
     * @param textLines the plain-text lines (may be {@code null})
     * @param htmlLines the HTML lines (may be {@code null})
     * @return the assembled statement document
     */
    private static StatementDocument documentWith(final List<StatementTransactionDto> txnLines,
                                                  final List<String> textLines,
                                                  final List<String> htmlLines) {
        return new StatementDocument(cardXref(), new Account(), new Customer(), new Card(),
                txnLines, textLines, htmlLines);
    }

    // =====================================================================
    // 1) Required cardXref + cardNumber() echo
    // =====================================================================

    @Nested
    @DisplayName("cardXref invariant and cardNumber() echo")
    class CardXrefContract {

        @Test
        @DisplayName("rejects a null cardXref")
        void rejectsNullCardXref() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementDocument(
                            null, new Account(), new Customer(), new Card(),
                            List.of(), List.of(), List.of()))
                    .withMessageContaining("cardXref must not be null");
        }

        @Test
        @DisplayName("cardNumber() returns the cross-reference card number")
        void cardNumberEchoesXref() {
            final StatementDocument document = documentWith(List.of(), List.of(), List.of());

            assertThat(document.cardNumber()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("exposes the supplied entity references unchanged")
        void exposesEntityReferences() {
            final CardXref xref = cardXref();
            final Account account = new Account();
            final Customer customer = new Customer();
            final Card card = new Card();

            final StatementDocument document = new StatementDocument(
                    xref, account, customer, card, List.of(), List.of(), List.of());

            assertThat(document.cardXref()).isSameAs(xref);
            assertThat(document.account()).isSameAs(account);
            assertThat(document.customer()).isSameAs(customer);
            assertThat(document.card()).isSameAs(card);
        }
    }

    // =====================================================================
    // 2) Null lists coalesce to empty
    // =====================================================================

    @Nested
    @DisplayName("null lists coalesce to empty immutable lists")
    class NullListCoalescing {

        @Test
        @DisplayName("null transaction/text/html lists become empty (never null)")
        void nullListsBecomeEmpty() {
            final StatementDocument document = documentWith(null, null, null);

            assertThat(document.transactionLines()).isNotNull().isEmpty();
            assertThat(document.textLines()).isNotNull().isEmpty();
            assertThat(document.htmlLines()).isNotNull().isEmpty();
        }
    }

    // =====================================================================
    // 3) Immutability and defensive copy
    // =====================================================================

    @Nested
    @DisplayName("stored lists are immutable defensive copies")
    class Immutability {

        @Test
        @DisplayName("text and html lines are stored verbatim and are unmodifiable")
        void storesAndProtectsLineLists() {
            final List<String> text = List.of("START OF STATEMENT", "END OF STATEMENT");
            final List<String> html = List.of("<html>", "</html>");

            final StatementDocument document = documentWith(List.of(), text, html);

            assertThat(document.textLines()).containsExactlyElementsOf(text);
            assertThat(document.htmlLines()).containsExactlyElementsOf(html);
            // Immutable: a structural mutation is rejected.
            assertThatThrownBy(() -> document.textLines().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> document.htmlLines().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a later mutation of the source list does not leak into the document")
        void defensivelyCopiesSourceList() {
            final List<String> mutableSource = new ArrayList<>(List.of("line-1"));

            final StatementDocument document = documentWith(List.of(), mutableSource, List.of());
            // Mutating the caller's list after construction must not affect the stored copy.
            mutableSource.add("line-2");

            assertThat(document.textLines()).containsExactly("line-1");
        }

        @Test
        @DisplayName("a list containing a null element is rejected (List.copyOf contract)")
        void rejectsNullElements() {
            final List<String> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatNullPointerException()
                    .isThrownBy(() -> documentWith(List.of(), withNull, List.of()));
        }
    }
}
