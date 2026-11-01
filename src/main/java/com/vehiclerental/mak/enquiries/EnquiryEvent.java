package com.vehiclerental.mak.enquiries;
import jakarta.persistence.*;import lombok.Getter;import lombok.Setter;import java.time.Instant;
@Entity @Table(name="website_enquiry_events") @Getter @Setter
public class EnquiryEvent {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 private long enquiryId;private long actorId;private String previousStatus;private String newStatus;
 private boolean noteChanged;private Instant createdAt;
}
