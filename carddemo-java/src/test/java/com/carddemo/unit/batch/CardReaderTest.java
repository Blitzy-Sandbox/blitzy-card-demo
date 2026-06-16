package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.CardReader;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link CardReader}.
 *
 * <p>Traceability (REFERENCE-ONLY; the COBOL program and copybook are not copied into the
 * target, source commit {@code 27d6c6f}): the reader re-platforms the sequential
 * {@code CARDFILE} read of batch program {@code app/cbl/CBACT02C.cbl}
 * ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL},
 * {@code FILE STATUS IS CARDFILE-STATUS}) over the 150-byte {@code CARD-RECORD} layout of
 * copybook {@code app/cpy/CVACT02Y.cpy}, whose primary key {@code CARD-NUM PIC X(16)} maps to
 * {@link Card#getCardNum()}. The COBOL skeleton drives the VSAM KSDS through an
 * {@code OPEN} / {@code READ NEXT} loop / {@code CLOSE} in ascending primary-key order; file
 * status {@code '00'} continues the loop and status {@code '10'} (end-of-file) ends it.</p>
 *
 * <p>These tests assert the binding parities of that re-platforming:</p>
 *
 * <ul>
 *   <li><b>Sequential KSDS read becomes ordered repository paging</b>: the reader scans
 *       {@code CardRepository.findAll(Pageable)} sorted ascending by {@code cardNum} with a
 *       fixed page size of 100, emitting each record exactly once and in key order.</li>
 *   <li><b>End-of-file becomes a clean null</b>: once the seeded rows are exhausted the reader
 *       fetches the next (empty) page and {@code read()} returns {@code null}, the Java
 *       equivalent of the COBOL {@code '10'} status that terminates the read loop.</li>
 *   <li><b>Reader identity is configured</b>: the inherited reader is named {@code cardReader}
 *       so Spring Batch can key its execution-context state.</li>
 * </ul>
 *
 * <p>{@link CardReader} extends {@code RepositoryItemReader} and configures the inherited
 * reader in {@code afterPropertiesSet()} (its constructor only late-binds the step-scoped
 * repository), so each test invokes {@code afterPropertiesSet()} exactly as the Spring
 * container would before {@code open()}. The single {@link CardRepository} collaborator is a
 * Mockito mock, so there is no Spring context, no Testcontainers, no LocalStack, and no live
 * AWS dependency.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardReader - ascending cardNum paging (page size 100), emit-once, clean EOF")
class CardReaderTest {

    /** Collaborator whose paged {@code findAll} the reader scans; mocked, never a real database. */
    @Mock
    private CardRepository cardRepository;

    /**
     * Builds a minimal {@link Card} carrying only the {@code CARD-NUM} primary key, which is
     * the sole field these mock-based tests exercise; no persistence occurs, so the remaining
     * non-null columns are irrelevant here.
     *
     * @param num the 16-character card number to assign as the entity key
     * @return a {@link Card} whose {@link Card#getCardNum()} equals {@code num}
     */
    private static Card card(String num) {
        Card c = new Card();
        c.setCardNum(num);
        return c;
    }

    @Test
    @DisplayName("pages findAll ascending by cardNum at size 100 and emits each row exactly once until exhaustion")
    void pagesAscendingByCardNum_pageSize100_emitsEachRowOnce() throws Exception {
        Card c1 = card("1234567890123450");
        Card c2 = card("1234567890123451");
        Page<Card> firstPage = new PageImpl<>(List.of(c1, c2));
        Page<Card> emptyPage = new PageImpl<>(Collections.emptyList());
        // Chained single-value stubs avoid a generic Page<Card>[] varargs array (a
        // thenReturn(first, empty) call would create one), keeping the -Werror build clean.
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(firstPage).thenReturn(emptyPage);

        CardReader reader = new CardReader(cardRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        // Each seeded row is emitted once, in repository (ascending cardNum) order; the
        // exhausted scan then fetches an empty page so read() returns null, mirroring the
        // COBOL '10' end-of-file status that terminates the CBACT02C read loop.
        assertThat(reader.read()).isSameAs(c1);
        assertThat(reader.read()).isSameAs(c2);
        assertThat(reader.read()).isNull();
        reader.close();

        // The first page request proves the ascending cardNum sort, the fixed page size 100,
        // and the zero-based starting page that reproduces the KSDS sequential key scan.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository, atLeastOnce()).findAll(captor.capture());
        Pageable first = captor.getAllValues().get(0);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getPageSize()).isEqualTo(100);
        assertThat(first.getSort().getOrderFor("cardNum")).isNotNull();
        assertThat(first.getSort().getOrderFor("cardNum").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("configures the inherited reader name as 'cardReader' for execution-context state")
    void configuresReaderName_cardReader() throws Exception {
        CardReader reader = new CardReader(cardRepository);
        reader.afterPropertiesSet();

        // getName() is the public ItemStreamSupport accessor for the configured reader name;
        // Spring Batch keys the reader's execution-context entries under this value.
        assertThat(reader.getName()).isEqualTo("cardReader");
    }
}
