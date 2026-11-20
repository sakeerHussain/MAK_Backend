package com.vehiclerental.mak.ratecards.entity;

import java.math.BigDecimal;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Price for one billing unit of a vehicle class, or of one vehicle when vehicleId is set (override). */
@Entity @Table(name = "rate_card_lines") @Getter @Setter
public class RateCardLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "rate_card_id") private RateCard card;
    @Column(nullable = false, length = 20) private String vehicleClass;
    private Long vehicleId;
    @Column(nullable = false, length = 10) private String unit;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amount;
}
