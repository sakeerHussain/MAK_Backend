package com.vehiclerental.mak.invoices.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
@Entity @Table(name="billing_profile") @Getter @Setter
public class BillingProfile {
 @Id private Long id;
 @Column(nullable=false) private Long version;
 @Column(nullable=false,columnDefinition="text") private String payload;
 @Column(nullable=false) private Instant updatedAt;
 private Long updatedBy;
}
