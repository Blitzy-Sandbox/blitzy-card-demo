package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;

/**
 * Card entity. JPA mapping of the COBOL CARD-RECORD (copybook CVACT02Y,
 * RECLN 150). Re-platforms the VSAM CARDDAT KSDS onto the PostgreSQL card table.
 */
@Entity
@Table(name = "card")
public class Card {

    @Id
    @Column(name = "card_num", nullable = false, length = 16)
    private String cardNum;

    @Column(name = "card_acct_id", nullable = false)
    private Long cardAcctId;

    @Column(name = "card_cvv_cd", nullable = false)
    private Integer cardCvvCd;

    @Column(name = "card_embossed_name", nullable = false, length = 50)
    private String cardEmbossedName;

    @Column(name = "card_expiraion_date", nullable = false, length = 10)
    private String cardExpiraionDate;

    @Column(name = "card_active_status", nullable = false, length = 1)
    private String cardActiveStatus;

    @Version
    @Column(name = "version")
    private Long version;

    public Card() {
    }

    public String getCardNum() { return cardNum; }
    public void setCardNum(String cardNum) { this.cardNum = cardNum; }
    public Long getCardAcctId() { return cardAcctId; }
    public void setCardAcctId(Long cardAcctId) { this.cardAcctId = cardAcctId; }
    public Integer getCardCvvCd() { return cardCvvCd; }
    public void setCardCvvCd(Integer cardCvvCd) { this.cardCvvCd = cardCvvCd; }
    public String getCardEmbossedName() { return cardEmbossedName; }
    public void setCardEmbossedName(String v) { this.cardEmbossedName = v; }
    public String getCardExpiraionDate() { return cardExpiraionDate; }
    public void setCardExpiraionDate(String v) { this.cardExpiraionDate = v; }
    public String getCardActiveStatus() { return cardActiveStatus; }
    public void setCardActiveStatus(String v) { this.cardActiveStatus = v; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        Card card = (Card) o;
        return Objects.equals(cardNum, card.cardNum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }
}
