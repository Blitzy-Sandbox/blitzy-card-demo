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
 * Unit tests for {@link CardCrossReferenceReader} — the Spring Batch reader that replaces the
 * sequential {@code XREFFILE} scan of COBOL {@code CBACT03C} over the 50-byte {@code CVACT03Y}
 * card cross-reference record layout (REFERENCE-ONLY, COBOL not copied; source commit
 * {@code 27d6c6f}).
 *
 * <p>These are pure-JVM tests: the {@link CardCrossReferenceRepository} is a Mockito mock returning
 * in-memory {@link PageImpl} pages, so no Spring context, Testcontainers, or LocalStack is involved.
 * The reader is an {@code InitializingBean}, so each fixture calls {@code afterPropertiesSet()}
 * (which wires the repository, the {@code findAll} method, the ascending {@code xrefCardNum} sort,
 * the page size of 100, and the reader name) exactly the way the Spring lifecycle would before
 * {@code open()}.
 *
 * <p>Three contracts are verified:
 * <ul>
 *   <li>the reader streams the VSAM KSDS primary-key order as an ascending, paged repository scan
 *       and emits each seeded record <em>exactly once</em> — the duplicate per-record
 *       {@code DISPLAY} in the source program ({@code CBACT03C} displays {@code CARD-XREF-RECORD}
 *       both in the driver loop and again inside {@code 1000-XREFFILE-GET-NEXT}) is a diagnostic
 *       artifact and is intentionally <em>not</em> reproduced as a double emission (AAP §0.8.1
 *       behavioral parity is to the data, not to the diagnostic output);</li>
 *   <li>the underlying {@code findAll(Pageable)} paging request asks for page 0, page size 100,
 *       sorted ascending by {@code xrefCardNum} (the {@code FD-XREF-CARD-NUM} record key);</li>
 *   <li>the reader registers the execution-context name {@code "cardCrossReferenceReader"} used to
 *       key its restart state.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardCrossReferenceReader — ascending xrefCardNum paged scan, page size 100, each row emitted exactly once")
class CardCrossReferenceReaderTest {

    /** Paged data source for the scan; stubbed to return in-memory pages of cross-reference rows. */
    @Mock
    private CardCrossReferenceRepository xrefRepository;

    /**
     * Builds a single {@link CardCrossReference} row from its three persistent fields, mirroring the
     * {@code CVACT03Y} layout ({@code XREF-CARD-NUM}, {@code XREF-CUST-ID}, {@code XREF-ACCT-ID}).
     *
     * @param cardNum the 16-character card number (the {@code @Id} key)
     * @param custId  the customer id
     * @param acctId  the account id
     * @return a populated cross-reference entity
     */
    private static CardCrossReference xref(String cardNum, Long custId, Long acctId) {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(cardNum);
        x.setXrefCustId(custId);
        x.setXrefAcctId(acctId);
        return x;
    }

    /**
     * Drains an opened reader to end-of-input, collecting every emitted item in order. Reading stops
     * when {@code read()} returns {@code null} (the COBOL {@code FILE STATUS '10'} end-of-file).
     *
     * @param reader an opened {@link CardCrossReferenceReader}
     * @return the items the reader emitted, in emission order
     * @throws Exception if a read fails
     */
    private static List<CardCrossReference> drain(CardCrossReferenceReader reader) throws Exception {
        List<CardCrossReference> read = new ArrayList<>();
        CardCrossReference item;
        while ((item = reader.read()) != null) {
            read.add(item);
        }
        return read;
    }

    @Test
    @DisplayName("emits each seeded row exactly once (COBOL double-DISPLAY artifact not reproduced)")
    void emitsEachSeededRowExactlyOnce() throws Exception {
        CardCrossReference x1 = xref("1111111111111111", 1L, 1001L);
        CardCrossReference x2 = xref("2222222222222222", 2L, 1002L);
        CardCrossReference x3 = xref("3333333333333333", 3L, 1003L);

        // First page returns the three seeded rows; the next page is empty so the scan terminates.
        when(xrefRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(x1, x2, x3)))
                .thenReturn(new PageImpl<>(List.of()));

        CardCrossReferenceReader reader = new CardCrossReferenceReader(xrefRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        List<CardCrossReference> read = drain(reader);

        // Count equals the seeded count (3): no row is emitted twice.
        assertThat(read).hasSize(3).containsExactly(x1, x2, x3);

        reader.close();
    }

    @Test
    @DisplayName("pages findAll(Pageable) ascending by xrefCardNum with page size 100 starting at page 0")
    void pagesAscendingByXrefCardNumWithPageSize100() throws Exception {
        CardCrossReference x1 = xref("1111111111111111", 1L, 1001L);
        CardCrossReference x2 = xref("2222222222222222", 2L, 1002L);
        CardCrossReference x3 = xref("3333333333333333", 3L, 1003L);

        when(xrefRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(x1, x2, x3)))
                .thenReturn(new PageImpl<>(List.of()));

        CardCrossReferenceReader reader = new CardCrossReferenceReader(xrefRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        drain(reader);
        reader.close();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(xrefRepository, atLeastOnce()).findAll(captor.capture());

        Pageable first = captor.getAllValues().get(0);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getPageSize()).isEqualTo(100);
        assertThat(first.getSort().getOrderFor("xrefCardNum")).isNotNull();
        assertThat(first.getSort().getOrderFor("xrefCardNum").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("registers the execution-context name \"cardCrossReferenceReader\"")
    void configuresReaderName() throws Exception {
        CardCrossReferenceReader reader = new CardCrossReferenceReader(xrefRepository);
        reader.afterPropertiesSet();

        assertThat(reader.getName()).isEqualTo("cardCrossReferenceReader");
    }
}
