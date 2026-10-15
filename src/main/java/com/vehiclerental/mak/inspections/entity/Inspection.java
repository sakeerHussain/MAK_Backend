package com.vehiclerental.mak.inspections.entity;

import java.time.*;
import java.util.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="inspections") @Getter @Setter
public class Inspection {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(unique=true,length=50) private String inspectionId;
    @Column(nullable=false) private Long vehicleId;
    @Column(nullable=false,length=30) private String type;
    @Column(name="vehicle_condition",nullable=false,length=20) private String condition;
    @Column(nullable=false) private LocalDate inspectionDate;
    @Column(nullable=false,length=200) private String inspector;
    @Column(nullable=false) private Long odometerKm;
    @Column(nullable=false) private Integer fuelLevel;
    private Long bookingId;
    @Column(columnDefinition="text") private String notes;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @ElementCollection @CollectionTable(name="inspection_photos",joinColumns=@JoinColumn(name="inspection_id"))
    @OrderColumn(name="photo_order") private List<InspectionPhoto> photos=new ArrayList<>();
}
