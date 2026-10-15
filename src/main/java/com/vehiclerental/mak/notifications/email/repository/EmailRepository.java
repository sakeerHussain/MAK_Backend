package com.vehiclerental.mak.notifications.email.repository;

import com.vehiclerental.mak.notifications.email.entity.EmailMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EmailRepository extends JpaRepository<EmailMessage,Long> {
    List<EmailMessage> findByRecordKindAndRecordIdOrderByIdDesc(String kind,long id);
    @Query("select e.id from EmailMessage e where e.status in ('Queued','Failed') and e.attempts < 5 and e.nextAttemptAt <= :now order by e.id")
    List<Long> due(@Param("now") Instant now, org.springframework.data.domain.Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmailMessage e where e.id=:id")
    Optional<EmailMessage> lockById(@Param("id") long id);
}
