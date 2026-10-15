package com.vehiclerental.mak.auth.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name="auth_control") @Getter @Setter
public class AuthControl { @Id private Integer id; @Column(nullable=false) private boolean bootstrapDone; }
