package com.carddemo.repository;

import com.carddemo.model.entity.Card;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Card entity (re-platforms the VSAM CARDDAT
 * KSDS and its CARDAIX alternate index). Provides keyed read by card number via
 * inherited operations, list and paginated access by account id (alternate
 * index / card-list browse), and inherited optimistic-locked update.
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    List<Card> findByCardAcctId(Long cardAcctId);

    Page<Card> findByCardAcctId(Long cardAcctId, Pageable pageable);
}
