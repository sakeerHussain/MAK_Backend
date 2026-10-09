package com.vehiclerental.mak.invoices;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

@Embeddable @Getter @Setter
public class InvoiceSourceId implements Serializable {
    private Long invoiceId;
    private Long rentalId;
    public InvoiceSourceId() {}
    public InvoiceSourceId(Long invoiceId, Long rentalId) { this.invoiceId = invoiceId; this.rentalId = rentalId; }
    @Override public boolean equals(Object other) { return other instanceof InvoiceSourceId id && Objects.equals(invoiceId,id.invoiceId) && Objects.equals(rentalId,id.rentalId); }
    @Override public int hashCode() { return Objects.hash(invoiceId,rentalId); }
}
