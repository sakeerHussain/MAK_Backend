package com.vehiclerental.mak.customers;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "customers") @Getter @Setter
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String type;
    @Column(nullable = false, length = 150) private String name;
    @Column(nullable = false, length = 150) private String nameKey;
    @Column(nullable = false, length = 40) private String phone;
    @Column(nullable = false, length = 150) private String email;
    @Column(length = 500) private String address;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private Instant createdAt;
    private String registrationNumber;
    private String taxNumber;
    private String billingContactName;
    @Column(length = 500) private String billingAddress;
    private String billingCity;
    private String billingCountry;
    private String paymentTerms;
    @Column(precision = 12, scale = 2) private BigDecimal waitingRatePerHour;
    private Integer includedWaitingMinutes;
    private Integer waitingBillingIncrementMinutes;
}
