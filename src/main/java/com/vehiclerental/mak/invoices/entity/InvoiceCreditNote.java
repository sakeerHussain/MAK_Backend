package com.vehiclerental.mak.invoices.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
@Entity @Table(name="invoice_credit_notes") @Getter @Setter
public class InvoiceCreditNote {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long invoiceId;
    @Column(nullable=false,length=36) private String requestKey;
    @Column(nullable=false,length=64) private String requestFingerprint;
    @Column(nullable=false,precision=14,scale=2) private BigDecimal totalAmount;
    @Column(nullable=false) private Instant issuedAt;
    @Column(nullable=false,columnDefinition="text") private String payload;
}
