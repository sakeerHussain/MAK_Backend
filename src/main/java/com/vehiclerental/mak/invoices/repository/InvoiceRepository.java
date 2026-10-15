package com.vehiclerental.mak.invoices.repository;

import com.vehiclerental.mak.invoices.entity.InvoiceRecord;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<InvoiceRecord, Long> {
    @Query("select i from InvoiceRecord i, InvoiceSource s where s.id.invoiceId = i.id and s.id.rentalId = :rentalId")
    Optional<InvoiceRecord> findForRental(long rentalId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InvoiceRecord i where i.id = :id")
    Optional<InvoiceRecord> lockById(long id);
}
