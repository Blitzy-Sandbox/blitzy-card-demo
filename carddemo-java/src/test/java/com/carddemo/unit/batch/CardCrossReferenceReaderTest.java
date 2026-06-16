package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.CardCrossReferenceReader;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.repository.CardCrossReferenceRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link CardCrossReferenceReader}.
 *
 * <p>Traceability (REFERENCE-ONLY; the COBOL program and copybook are not copied into the
 * target, source commit {@code 27d6c6f}): the reader re-platforms the sequential
 * {@code XREFFILE-FILE} read of batch program {@code app/cbl/CBACT03C.cbl}
 * ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL},
 * {@code RECORD KEY IS FD-XREF-CARD-NUM}) over the 50-byte {@code CARD-XREF-RECORD} layout of
 * copybook {@code app/cpy/CVACT03Y.cpy}. These JVM-only tests pin the binding parities of that
 * re-platforming:</p>
 *
 * <ul>
 *   <li><b>Sequential VSAM read &rarr; ordered repository paging</b> (AAP section 0.8.1 / 0.5.1):
 *       the inherited {@link org.springframework.batch.item.data.RepositoryItemReader} pages
 *       through {@code CardCrossReferenceRepository.findAll(Pageable)} sorted <em>ascending</em>
 *       by the {@code @Id} property {@code xrefCardNum} (the primary-key read order of the VSAM
 *       KSDS) with a page size of 100, starting at page index 0.</li>
 *   <li><b>Each record emitted exactly once</b>: COBOL {@code CBACT03C} issues two
 *       {@code DISPLAY CARD-XREF-RECORD} statements per record (once inside
 *       {@code 1000-XREFFILE-GET-NEXT} and again in the main loop) &mdash; a diagnostic artifact,
 *       not a double read. The migration preserves fidelity to the <em>data</em> (one row per
 *       record), not to the duplicate console output, so the reader emits every seeded row once
 *       and only once and then returns {@code null} at exhaustion so Spring Batch stops the
 *       step.</li>
 * </ul>
 *
 * <p>The reader's only collaborator is the {@link CardCrossReferenceRepository}, so these tests
 * mock just that repository (Mockito) and feed it through {@link PageImpl} pages: no Spring
 * context, no Testcontainers, no LocalStack, no database, and no network. Because the production
 * class configures the inherited reader in {@code afterPropertiesSet()} (not in its constructor),
 * each test invokes {@code afterPropertiesSet()} exactly as the Spring container would before
 * {@code open()}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardCrossReferenceReader - CBACT03C ascending paged scan, each row emitted exactly once")
class CardCrossReferenceReaderTest {

    /** The single collaborator: the cross-reference repository scanned page-by-page. */
    @Mock
    private CardCrossReferenceRepository xrefRepository;

    /**
     * Verifies the headline parity: every seeded cross-reference row is emitted once and only
     * once. Three distinct rows are returned as a single page, followed by an empty page so the
     * paged reader terminates; draining {@code read()} to {@code null} must yield exactly the
     * three seeded rows in order, with no duplicate emission of the COBOL double-{@code DISPLAY}
     * artifact.
     */
    @Test
    @DisplayName("emits each seeded row exactly once (double-DISPLAY artifact not reproduced)")
    void emitsEachSeededRowExactlyOnce() throws Exception {
        CardCrossReference x1 = xref("1111111111111111", 1L, 1001L);
        CardCrossReference x2 = xref("2222222222222222", 2L, 1002L);
        CardCrossReference x3 = xref("3333333333333333", 3L, 1003L);

        CardCrossReferenceReader reader = openSeededReader(List.of(x1, x2, x3));
        List<CardCrossReference> read = drain(reader);
        reader.close();

        assertThat(read).containsExactly(x1, x2, x3);
    }

    /**
     * Verifies the read order and paging contract: the first page request issued to the
     * repository asks for page index 0, size 100, sorted ascending by {@code xrefCardNum} &mdash;
     * the deterministic equivalent of the COBOL sequential primary-key read.
     */
    @Test
    @DisplayName("scans ascending by xrefCardNum in pages of 100 starting at page 0")
    void scansAscendingByCardNumberInPagesOf100() throws Exception {
        CardCrossReference x1 = xref("1111111111111111", 1L, 1001L);
        CardCrossReference x2 = xref("2222222222222222", 2L, 1002L);
        CardCrossReference x3 = xref("3333333333333333", 3L, 1003L);

        CardCrossReferenceReader reader = openSeededReader(List.of(x1, x2, x3));
        drain(reader);
        reader.close();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(xrefRepository, atLeastOnce()).findAll(captor.capture());
        Pageable first = captor.getAllValues().get(0);

        assertThat(first.getPageSize()).isEqualTo(100);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getSort().getOrderFor("xrefCardNum")).isNotNull();
        assertThat(first.getSort().getOrderFor("xrefCardNum").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    /**
     * Verifies the reader registers the stable step name {@code "cardCrossReferenceReader"} used
     * to key its state in the Spring Batch {@link ExecutionContext}. Configuration happens in
     * {@code afterPropertiesSet()}; the inherited public {@code getName()} exposes the result, so
     * no opening or repository interaction is required here.
     */
    @Test
    @DisplayName("configures the step-scoped reader name")
    void configuresReaderName() throws Exception {
        CardCrossReferenceReader reader = new CardCrossReferenceReader(xrefRepository);
        reader.afterPropertiesSet();

        assertThat(reader.getName()).isEqualTo("cardCrossReferenceReader");
    }

    /**
     * Stubs the repository to return the supplied rows as the first page and an empty page
     * thereafter (so the paged reader cleanly reaches end-of-data), then constructs, configures,
     * and opens the reader exactly as the Spring container would. Consecutive single-value
     * {@code thenReturn} calls are used (rather than a single varargs call) to keep the build
     * warning-free under {@code -Xlint:all -Werror}.
     *
     * @param firstPage the rows to be returned on the first page request
     * @return an opened reader ready to be drained via {@link #drain(CardCrossReferenceReader)}
     */
    private CardCrossReferenceReader openSeededReader(List<CardCrossReference> firstPage)
            throws Exception {
        when(xrefRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(firstPage))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        CardCrossReferenceReader reader = new CardCrossReferenceReader(xrefRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());
        return reader;
    }

    /**
     * Drains the reader by calling {@code read()} until it returns {@code null} at end-of-data,
     * collecting every emitted row in order.
     *
     * @param reader the opened reader to exhaust
     * @return the rows emitted, in read order
     */
    private static List<CardCrossReference> drain(CardCrossReferenceReader reader) throws Exception {
        List<CardCrossReference> read = new ArrayList<>();
        CardCrossReference item;
        while ((item = reader.read()) != null) {
            read.add(item);
        }
        return read;
    }

    /**
     * Builds a {@link CardCrossReference} from the three mapped fields of the {@code CVACT03Y}
     * record layout ({@code XREF-CARD-NUM}, {@code XREF-CUST-ID}, {@code XREF-ACCT-ID}).
     *
     * @param cardNum the 16-character card number (the {@code @Id} {@code xrefCardNum})
     * @param custId  the customer id ({@code xrefCustId})
     * @param acctId  the account id ({@code xrefAcctId})
     * @return a populated cross-reference row
     */
    private static CardCrossReference xref(String cardNum, Long custId, Long acctId) {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(cardNum);
        x.setXrefCustId(custId);
        x.setXrefAcctId(acctId);
        return x;
    }
}
