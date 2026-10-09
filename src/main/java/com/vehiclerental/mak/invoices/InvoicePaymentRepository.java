package com.vehiclerental.mak.invoices;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, Long> {
    List<InvoicePayment> findByInvoiceIdOrderByIdAsc(long invoiceId);
}
