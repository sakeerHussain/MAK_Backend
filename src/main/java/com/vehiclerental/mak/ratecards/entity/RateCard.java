package com.vehiclerental.mak.ratecards.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "rate_cards") @Getter @Setter
public class RateCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, unique = true) private int revision;
    @Column(nullable = false, length = 12) private String status;
    @Column(nullable = false) private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @Column(nullable = false, precision = 5, scale = 2) private BigDecimal taxPercent;
    @Column(nullable = false) private int graceMinutes;
    @Column(precision = 12, scale = 2) private BigDecimal extraKmRate;
    @Column(precision = 12, scale = 2) private BigDecimal overtimeHourRate;
    @Column(precision = 12, scale = 2) private BigDecimal extraDriverRate;
    @Column(length = 1000) private String notes;
    private Long revisedFromId;
    @Column(length = 64) private String digest;
    @Column(nullable = false) private Long createdBy;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    private Long publishedBy;
    private Instant publishedAt;
    @Version private long version;
    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("vehicleClass ASC, unit ASC, id ASC")
    private List<RateCardLine> lines = new ArrayList<>();
}
