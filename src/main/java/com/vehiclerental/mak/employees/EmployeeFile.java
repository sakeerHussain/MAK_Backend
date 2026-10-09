package com.vehiclerental.mak.employees;

import java.time.Instant;
import java.time.LocalDate;

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

@Entity
@Table(name = "employee_files")
@Getter
@Setter
public class EmployeeFile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "employee_id") private Employee employee;
    @Column(nullable = false, length = 20) private String kind;
    private String documentType;
    private LocalDate expiryDate;
    @Column(unique = true) private String storageKey;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    @Column(nullable = false) private Instant createdAt;
}
