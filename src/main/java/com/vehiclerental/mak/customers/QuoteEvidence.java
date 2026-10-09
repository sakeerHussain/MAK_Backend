package com.vehiclerental.mak.customers;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "quote_evidence") @Getter @Setter
public class QuoteEvidence {
    @Id @Column(length = 36) private String quoteId;
    @Column(nullable = false) private String fileName;
    @Column(nullable = false) private String contentType;
    @Column(nullable = false, columnDefinition = "bytea") private byte[] fileData;
}
