package com.vehiclerental.mak.fleet.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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

@Entity
@Table(name = "vehicles")
@Getter
@Setter
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 50)
    private String registration;
    @Column(nullable = false, unique = true, length = 50)
    private String vehicleId;
    @Column(nullable = false, length = 100)
    private String make;
    @Column(nullable = false, length = 100)
    private String model;
    @Column(name = "registration_year", nullable = false)
    private Integer year;
    private String color;
    private String doorNo;
    private String chassisNo;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private Integer seats;
    @Column(nullable = false)
    private String transmission;
    @Column(nullable = false)
    private String fuelType;
    @Column(precision = 12, scale = 2)
    private BigDecimal hourlyRate;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal dailyRate;
    @Column(precision = 12, scale = 2)
    private BigDecimal monthlyRate;
    @Column(nullable = false, length = 3)
    private String rateCurrency;
    @Column(nullable = false)
    private String ownership;
    private String vendor;
    @Column(nullable = false)
    private String status;
    private String custodian;
    @Column(length=30) private String maintenanceReturnStatus;
    @Column(nullable = false)
    private boolean onSite;
    @Column(nullable = false)
    private boolean listedForBooking;
    @Column(nullable = false)
    private boolean showOnPublicSite;
    private Long serviceIntervalKm;
    private Long lastServiceKm;
    private Long currentOdometerKm;
    private Long oilIntervalKm;
    private Long lastOilKm;
    private Long tyreIntervalKm;
    private Long lastTyreKm;
    private Long brakeIntervalKm;
    private Long lastBrakeKm;
    private LocalDate registrationExpiryDate;
    private LocalDate insuranceExpiryDate;
    private LocalDate inspectionExpiryDate;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "vehicle_tags", joinColumns = @JoinColumn(name = "vehicle_id"))
    @OrderColumn(name = "tag_order")
    @Column(name = "tag", nullable = false, length = 80)
    private List<String> featureTags = new ArrayList<>();
}
