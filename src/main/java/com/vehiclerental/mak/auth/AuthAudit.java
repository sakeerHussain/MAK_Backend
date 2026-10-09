package com.vehiclerental.mak.auth;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="auth_audit") @Getter @Setter
public class AuthAudit {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 private Long actorId;
 private Long targetId;
 @Column(nullable=false,length=40) private String event;
 @Column(nullable=false,length=300) private String summary;
 @Column(nullable=false) private Instant createdAt;
}
