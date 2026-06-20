package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.CardReader;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
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
 * Unit tests for {@link CardReader} — the Spring Batch reader that replaces the sequential
 * {@code CARDFILE} read of COBOL {@code CBACT02C} over the 150-byte {@code CVACT02Y}
 * {@code CARD-RECORD} layout (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}).
 *
 * <p>These are pure-JVM tests: {@link CardRepository} is a Mockito mock, so there is no Spring
 * context, Testcontainers, or LocalStack. {@code CardReader} is a {@code RepositoryItemReader}
 * and an {@code InitializingBean}; each fixture calls {@code afterPropertiesSet()} (which applies
 * the {@code findAll} method, the ascending {@code cardNum} sort, the 100-row page size, and the
 * {@code cardReader} stream name) and then {@code open(ExecutionContext)} exactly as the Spring
 * step lifecycle would before the first {@code read()}.
 *
 * <p>The COBOL {@code OPEN} / {@code READ NEXT} (FILE STATUS {@code '00'}) / {@code CLOSE} cycle
 * over the indexed {@code CARDFILE} ({@code ACCESS MODE SEQUENTIAL}, {@code RECORD KEY
 * FD-CARD-NUM}) maps to a sorted, paged repository scan. Two binding contracts are verified:
 * <ul>
 *   <li><b>Ordered sequential paging</b> — the reader pages through
 *       {@link CardRepository#findAll(Pageable)} ascending by the {@code cardNum} primary key with
 *       a page size of 100, mirroring the VSAM key-sequenced read; every buffered row is emitted
 *       exactly once and end of file (COBOL FILE STATUS {@code '10'}) is signalled by a single
 *       {@code null}.</li>
 *   <li><b>Stream identity</b> — the reader's execution-context name is {@code cardReader}, the
 *       handle Spring Batch uses to persist and restore read state for the step.</li>
 * </ul>
 *
 * <p>No feature beyond the COBOL behavior is exercised (AAP §0.8.1 "no feature expansion").
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardReader — ascending-cardNum, page-size-100 sequential repository scan (CBACT02C)")
class CardReaderTest {

    /** Page size configured by {@code CardReader.afterPropertiesSet()} (one full VSAM browse page). */
    private static final int EXPECTED_PAGE_SIZE = 100;

    /** Property the reader sorts on, matching the {@code Card} primary key / VSAM {@code FD-CARD-NUM}. */
    private static final String SORT_PROPERTY = "cardNum";

    /** Stream name configured by the reader, used by Spring Batch for execution-context keys. */
    private static final String READER_NAME = "cardReader";

    /** Card master repository (VSAM {@code CARDDAT} KSDS), mocked to supply paged {@code findAll} results. */
    @Mock
    private CardRepository cardRepository;

    /**
     * Builds a {@link Card} carrying only its primary key. The reader returns the exact instances
     * supplied by the repository (verified by reference identity), and the mock performs no
     * persistence, so no other field is required; the 16-character {@code cardNum} mirrors the
     * fixed-width {@code CARD-NUM PIC X(16)} key.
     *
     * @param num the 16-character card number to assign as the primary key
     * @return a {@link Card} with {@code cardNum} set to {@code num}
     */
    private static Card card(String num) {
        Card c = new Card();
        c.setCardNum(num);
        return c;
    }

    @Test
    @DisplayName("pages findAll ascending by cardNum at size 100, page 0 first, emitting each row once until EOF")
    void pagesAscendingByCardNum_pageSize100_emitsEachRowOnce() throws Exception {
        Card c1 = card("1234567890123450");
        Card c2 = card("1234567890123451");
        // First page returns the two seeded rows; the next page is empty so read() returns null
        // (the COBOL FILE STATUS '10' end-of-file). Chained single-argument stubbing is used
        // deliberately: the varargs thenReturn(T, T...) would force an unchecked generic array
        // creation that the -Xlint:all -Werror build (Gate 2) rejects.
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c1, c2)))
                .thenReturn(new PageImpl<>(List.of()));

        CardReader reader = new CardReader(cardRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        // Each buffered row is emitted exactly once, in repository (ascending key) order, then EOF.
        assertThat(reader.read()).isSameAs(c1);
        assertThat(reader.read()).isSameAs(c2);
        assertThat(reader.read()).isNull();
        reader.close();

        // The reader requests pages from findAll; capture every Pageable it constructed.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository, atLeastOnce()).findAll(captor.capture());

        // First request: page 0, size 100, sorted ascending by cardNum (the VSAM key sequence).
        Pageable first = captor.getAllValues().get(0);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getPageSize()).isEqualTo(EXPECTED_PAGE_SIZE);
        Sort.Order cardNumOrder = first.getSort().getOrderFor(SORT_PROPERTY);
        assertThat(cardNumOrder).isNotNull();
        assertThat(cardNumOrder.getDirection()).isEqualTo(Sort.Direction.ASC);

        // The reader pages forward: a second request for the (empty) next page at the same size.
        assertThat(captor.getAllValues()).hasSize(2);
        Pageable second = captor.getAllValues().get(1);
        assertThat(second.getPageNumber()).isEqualTo(1);
        assertThat(second.getPageSize()).isEqualTo(EXPECTED_PAGE_SIZE);
    }

    @Test
    @DisplayName("configures the Spring Batch stream name as 'cardReader'")
    void configuresStreamName_asCardReader() throws Exception {
        CardReader reader = new CardReader(cardRepository);
        reader.afterPropertiesSet();

        assertThat(reader.getName()).isEqualTo(READER_NAME);
    }
}
