package com.vehiclerental.mak.inspections.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Embeddable @Getter @Setter
public class InspectionPhoto {
    @Column(nullable=false,length=100) private String storageKey;
    @Column(nullable=false,length=255) private String name;
    @Column(nullable=false,length=100) private String contentType;
    @Column(nullable=false) private Long fileSize;
}
