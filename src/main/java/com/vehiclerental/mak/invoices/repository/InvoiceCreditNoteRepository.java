package com.vehiclerental.mak.invoices.repository;
import com.vehiclerental.mak.invoices.entity.InvoiceCreditNote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InvoiceCreditNoteRepository extends JpaRepository<InvoiceCreditNote,Long> {
    List<InvoiceCreditNote> findByInvoiceIdOrderByIdAsc(long invoiceId);
    Optional<InvoiceCreditNote> findByInvoiceIdAndRequestKey(long invoiceId,String requestKey);
}
