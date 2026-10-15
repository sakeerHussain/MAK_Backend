package com.vehiclerental.mak.notifications.email.entity;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="email_outbox") @Getter @Setter
public class EmailMessage {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=20) private String recordKind;
    @Column(nullable=false) private Long recordId;
    @Column(nullable=false,length=254) private String recipient;
    @Column(nullable=false,length=200) private String subject;
    @Column(nullable=false,columnDefinition="text") private String body;
    @Column(columnDefinition="text") private String attachment;
    @Column(length=100) private String attachmentName;
    @Column(nullable=false,length=20) private String status;
    @Column(nullable=false) private int attempts;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant nextAttemptAt;
    private Instant sentAt;
    @Column(length=300) private String lastError;
}
