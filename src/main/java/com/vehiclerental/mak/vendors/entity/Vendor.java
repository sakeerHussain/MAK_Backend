package com.vehiclerental.mak.vendors.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "vendors") @Getter @Setter
public class Vendor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String vendorType;
    @Column(nullable = false, length = 150) private String name;
    @Column(nullable = false, unique = true, length = 150) private String nameKey;
    private String crNumber;
    private String vatNumber;
    @Column(length = 500) private String nationalAddress;
    private String classification;
    @Column(nullable = false, precision = 5, scale = 2) private BigDecimal commission;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private Integer creditTerms;
    private LocalDate contractStart;
    private LocalDate contractEnd;
    private String beneficiaryName;
    private String bankName;
    private String iban;
    private String accountNumber;
    private String swiftCode;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false) private boolean archived;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "vendor_contacts", joinColumns = @JoinColumn(name = "vendor_id"))
    @OrderColumn(name = "position")
    private List<Contact> contacts = new ArrayList<>();

    @Embeddable @Getter @Setter
    public static class Contact {
        @Column(name = "name", nullable = false, length = 150) private String name;
        @Column(name = "mobile", nullable = false, length = 40) private String mobile;
        @Column(name = "email", length = 150) private String email;
    }
}
