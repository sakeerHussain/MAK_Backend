package com.vehiclerental.mak.auth.entity;
import jakarta.persistence.*;import lombok.*;import java.time.Instant;
@Entity @Table(name="auth_invitations") @Getter @Setter
public class Invitation {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=200) private String name;
 @Column(nullable=false,length=254) private String email;
 @Column(nullable=false,length=20) private String role;
 private Long customRoleId;
 @Column(nullable=false,length=64,unique=true) private String tokenHash;
 @Column(nullable=false,length=20) private String status;
 @Column(nullable=false) private Instant createdAt;
 @Column(nullable=false) private Instant expiresAt;
 @Column(nullable=false) private Long createdBy;
}
