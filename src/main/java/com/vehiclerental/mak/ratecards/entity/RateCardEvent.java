package com.vehiclerental.mak.ratecards.entity;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "rate_card_events") @Getter @Setter
public class RateCardEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "rate_card_id", nullable = false) private Long rateCardId;
    @Column(nullable = false) private Long actorId;
    @Column(nullable = false, length = 20) private String action;
    @Column(length = 500) private String reason;
    @Column(nullable = false) private Instant createdAt;
}
