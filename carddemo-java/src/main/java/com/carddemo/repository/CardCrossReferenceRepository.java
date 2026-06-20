package com.carddemo.repository;

import com.carddemo.model.entity.CardCrossReference;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the CardCrossReference entity (re-platforms the
 * VSAM CARDXREF KSDS and its CXACAIX alternate index). Provides keyed read by
 * card number via inherited operations and list access by account id (the
 * non-unique alternate index used to resolve the card/account/customer linkage).
 */
@Repository
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {

    List<CardCrossReference> findByXrefAcctId(Long xrefAcctId);
}
