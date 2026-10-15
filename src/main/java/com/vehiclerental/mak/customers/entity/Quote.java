package com.vehiclerental.mak.customers.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "customer_quotes") @Getter @Setter
public class Quote {
    @Id @Column(length = 36) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id") private Customer customer;
    @Column(nullable = false, unique = true, length = 60) private String number;
    @Column(nullable = false) private int version;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private LocalDate issueDate;
    private LocalDate offerValidUntil;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @Column(nullable = false, precision = 5, scale = 2) private BigDecimal taxPercent;
    private String paymentTerms;
    @Column(columnDefinition = "text") private String notes;
    @Column(nullable = false, columnDefinition = "text") private String routesJson;
    @Column(nullable = false, columnDefinition = "text") private String monthlyJson;
    @Column(nullable = false, columnDefinition = "text") private String servicesJson;
    @Column(nullable = false, columnDefinition = "text") private String extrasJson;
    private String approvedBy;
    private LocalDate approvalDate;
    private String approvalMethod;
    @Column(length = 1000) private String approvalNote;
    private String approvalFileName;
    @Column(nullable = false) private Instant createdAt;
}
