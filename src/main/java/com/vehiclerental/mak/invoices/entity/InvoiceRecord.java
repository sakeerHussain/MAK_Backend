package com.vehiclerental.mak.invoices.entity;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "invoices") @Getter @Setter
public class InvoiceRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, length = 50) private String number;
    @Column(nullable = false, length = 20) private String kind;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal totalAmount;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal paidAmount;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(nullable = false) private Instant createdAt;
    private Instant issuedAt;
}
