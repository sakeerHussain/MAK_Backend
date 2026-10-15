package com.vehiclerental.mak.invoices.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "invoice_sources") @Getter @Setter
public class InvoiceSource {
    @EmbeddedId private InvoiceSourceId id;
}
