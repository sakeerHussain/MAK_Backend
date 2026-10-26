package com.vehiclerental.mak.fleet.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "vehicle_import_drafts") @Getter @Setter
public class VehicleImportDraft {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private long version;
    @Column(nullable = false, unique = true, length = 50) private String registration;
    @Column(nullable = false, unique = true, length = 50) private String vehicleId;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(nullable = false) private Instant createdAt;
}
