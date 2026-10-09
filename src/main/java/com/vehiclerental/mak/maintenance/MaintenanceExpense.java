package com.vehiclerental.mak.maintenance;
import java.math.BigDecimal;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Embeddable @Getter @Setter
public class MaintenanceExpense {
 @Column(nullable=false,length=80) private String clientKey;
 @Column(nullable=false,length=30) private String category;
 @Column(nullable=false,precision=12,scale=2) private BigDecimal amount;
 @Column(nullable=false,length=1000) private String description;
 @Column(length=100) private String receiptKey;
 @Column(length=255) private String receiptName;
 @Column(length=100) private String receiptType;
 private Long receiptSize;
}
