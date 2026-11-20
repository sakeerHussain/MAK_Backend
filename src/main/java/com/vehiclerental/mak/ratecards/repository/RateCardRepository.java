package com.vehiclerental.mak.ratecards.repository;

import com.vehiclerental.mak.ratecards.entity.RateCard;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateCardRepository extends JpaRepository<RateCard, Long> {
    List<RateCard> findAllByOrderByRevisionDesc();

    @Query("select coalesce(max(c.revision), 0) from RateCard c")
    int maxRevision();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RateCard c where c.id = :id")
    Optional<RateCard> lockById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RateCard c where c.status = 'PUBLISHED' order by c.effectiveFrom desc")
    List<RateCard> lockPublished();

    /** The published revision in effect on a date (KSA calendar day). */
    @Query("select c from RateCard c where c.status = 'PUBLISHED' and c.effectiveFrom <= :day"
            + " and (c.effectiveTo is null or c.effectiveTo >= :day) order by c.effectiveFrom desc")
    List<RateCard> findActive(@Param("day") LocalDate day);
}
