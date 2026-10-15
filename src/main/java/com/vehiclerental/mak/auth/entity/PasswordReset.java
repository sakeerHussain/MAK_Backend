package com.vehiclerental.mak.auth.entity;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name="auth_password_resets") @Getter @Setter
public class PasswordReset { @Id @Column(length=64) private String tokenHash; @Column(nullable=false) private Long accountId; @Column(nullable=false) private Instant expiresAt; }
