package com.vehiclerental.mak.invoices.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "invoice_payments") @Getter @Setter
public class InvoicePayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long invoiceId;
    @Column(nullable = false, length = 10) private String kind;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(nullable = false) private LocalDate paidOn;
    private String method;
    private String reference;
}
