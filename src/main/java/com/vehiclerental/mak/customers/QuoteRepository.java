package com.vehiclerental.mak.customers;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface QuoteRepository extends JpaRepository<Quote, String> {
    List<Quote> findByCustomerIdOrderByVersionDesc(long customerId);
    boolean existsByNumberIgnoreCaseAndIdNot(String number, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quote q where q.id = :id")
    Optional<Quote> lockById(String id);
}
