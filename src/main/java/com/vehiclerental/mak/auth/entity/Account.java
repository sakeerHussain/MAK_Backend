package com.vehiclerental.mak.auth.entity;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name="auth_accounts") @Getter @Setter
public class Account {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=200) private String name;
 @Column(nullable=false,unique=true,length=254) private String email;
 @Column(nullable=false,length=100) private String passwordHash;
 @Column(nullable=false,length=20) private String role;
 @Column(nullable=false,length=20) private String status;
 @Column(nullable=false) private long sessionVersion;
 @Column(nullable=false) private int failedAttempts;
 private Instant lockedUntil;
 @Column(nullable=false) private Instant createdAt;
 private Instant lastLoginAt;
}
