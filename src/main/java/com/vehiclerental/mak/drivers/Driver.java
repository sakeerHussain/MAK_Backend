package com.vehiclerental.mak.drivers;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "drivers")
@Getter
@Setter
public class Driver {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 50) private String employeeId;
    @Column(nullable = false, length = 150) private String name;
    @Column(nullable = false, length = 40) private String phone;
    @Column(nullable = false, unique = true, length = 40) private String phoneKey;
    private String email;
    @Column(nullable = false) private String category;
    private String nationality;
    @Column(nullable = false) private String designation;
    private String idNumber;
    private LocalDate iqamaExpiry;
    private Integer yearsExperience;
    @Column(nullable = false) private String employmentType;
    private String vendor;
    @Column(nullable = false) private String status;
    private String companyId;
    private LocalDate joinedOn;
    private String officialNumber;
    @Column(precision = 12, scale = 2) private BigDecimal hourlyRate;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal dailyRate;
    @Column(precision = 12, scale = 2) private BigDecimal monthlyRate;
    private String passportNumber;
    private LocalDate passportExpiry;
    private String contractId;
    private LocalDate contractExpiry;
    private String licenceNumber;
    private LocalDate licenceExpiry;
    private String bloodGroup;
    private String insuranceNumber;
    private LocalDate insuranceExpiry;
    private String emergencyKsa;
    private String emergencyHome;
    @Column(length = 500) private String homeAddress;
    @Column(nullable = false) private int bookingCount;
    @Column(nullable = false) private int paymentCount;
    @Column(nullable = false) private boolean archived;
}
