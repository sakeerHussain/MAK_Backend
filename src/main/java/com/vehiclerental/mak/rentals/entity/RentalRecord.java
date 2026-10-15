package com.vehiclerental.mak.rentals.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "rental_records") @Getter @Setter
public class RentalRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, length = 50) private String reference;
    @Column(nullable = false, length = 20) private String kind;
    @Column(nullable = false, length = 20) private String bookingType;
    @Column(nullable = false, length = 20) private String status;
    private Long customerId;
    private Long vehicleId;
    private Long driverId;
    @Column(nullable = false) private LocalDateTime pickupAt;
    @Column(nullable = false) private LocalDateTime returnAt;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal totalAmount;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal receivedAmount;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal refundedAmount;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(nullable = false) private Instant createdAt;
}
