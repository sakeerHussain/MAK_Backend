package com.vehiclerental.mak.vendors.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "vendor_files") @Getter @Setter
public class VendorFile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "vendor_id") private Vendor vendor;
    @Column(nullable = false, length = 40) private String documentType;
    @Column(nullable = false, unique = true, length = 100) private String storageKey;
    @Column(nullable = false, length = 255) private String originalName;
    @Column(nullable = false, length = 100) private String contentType;
    @Column(nullable = false) private Long sizeBytes;
    @Column(nullable = false) private Instant createdAt;
}
