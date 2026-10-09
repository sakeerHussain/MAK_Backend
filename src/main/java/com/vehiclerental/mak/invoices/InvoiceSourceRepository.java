package com.vehiclerental.mak.invoices;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InvoiceSourceRepository extends JpaRepository<InvoiceSource, InvoiceSourceId> {
    List<InvoiceSource> findByIdInvoiceId(long invoiceId);
    boolean existsByIdRentalId(long rentalId);
    void deleteByIdInvoiceId(long invoiceId);
}
