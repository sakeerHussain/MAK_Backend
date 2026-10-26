package com.vehiclerental.mak.auth.entity;
import jakarta.persistence.*;import lombok.*;import java.time.Instant;
@Entity @Table(name="auth_roles") @Getter @Setter
public class CustomRole {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=80,unique=true) private String name;
 @Column(nullable=false,length=300) private String description;
 @Column(nullable=false,columnDefinition="text") private String permissionsJson;
 @Column(nullable=false) private long version;
 @Column(nullable=false) private Instant createdAt;
 @Column(nullable=false) private Instant updatedAt;
}
