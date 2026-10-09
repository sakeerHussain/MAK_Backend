package com.vehiclerental.mak.rentals;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "rental_payments") @Getter @Setter
public class RentalPayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long rentalId;
    @Column(nullable = false, length = 10) private String kind;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(nullable = false) private Instant createdAt;
}
