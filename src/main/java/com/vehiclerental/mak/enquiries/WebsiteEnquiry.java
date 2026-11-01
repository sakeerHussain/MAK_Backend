package com.vehiclerental.mak.enquiries;
import jakarta.persistence.*;import lombok.Getter;import lombok.Setter;
import java.time.*;
@Entity @Table(name="website_enquiries") @Getter @Setter
public class WebsiteEnquiry {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=36,unique=true) private String submissionId;
 @Column(nullable=false,length=64) private String fingerprint;
 @Column(nullable=false,length=10) private String kind;
 @Column(nullable=false,length=120) private String name;
 @Column(nullable=false,length=254) private String email;
 @Column(length=40) private String phone;
 @Column(length=150) private String company;
 @Column(nullable=false,length=120) private String subject;
 @Column(nullable=false,length=4000) private String message;
 @Column(length=80) private String category;
 @Column(length=80) private String preference;
 @Column(length=300) private String location;
 private LocalDate pickupDate;private LocalDate returnDate;private Long vehicleId;
 @Column(length=200) private String vehicleName;
 @Column(nullable=false,length=20) private String status;
 @Column(nullable=false,length=4000) private String internalNote="";
 @Version private long version;
 @Column(nullable=false) private Instant createdAt;
 @Column(nullable=false) private Instant updatedAt;
 private Long updatedBy;
}
