package com.vehiclerental.mak.maintenance.entity;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="maintenance_jobs") @Getter @Setter
public class MaintenanceJob {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(unique=true,length=50) private String jobId;
 @Column(nullable=false) private Long vehicleId;
 @Column(nullable=false,length=40) private String workType;
 @Column(nullable=false,length=80) private String workItem;
 @Column(nullable=false,length=20) private String status;
 @Column(nullable=false) private LocalDate serviceDate;
 private Long odometerKm;
 @Column(length=200) private String garage;
 private LocalDate nextServiceDate;
 private Long nextServiceOdometerKm;
 @Column(columnDefinition="text") private String notes;
 @Column(nullable=false,columnDefinition="text") private String completedTasks;
 @Column(nullable=false,precision=14,scale=2) private BigDecimal totalAmount;
 @Column(nullable=false) private Instant createdAt;
 @Column(nullable=false) private Instant updatedAt;
 @ElementCollection @CollectionTable(name="maintenance_expenses",joinColumns=@JoinColumn(name="job_id"))
 @OrderColumn(name="line_order") private List<MaintenanceExpense> expenseLines=new ArrayList<>();
}
