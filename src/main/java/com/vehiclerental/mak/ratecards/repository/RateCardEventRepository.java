package com.vehiclerental.mak.ratecards.repository;

import com.vehiclerental.mak.ratecards.entity.RateCardEvent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateCardEventRepository extends JpaRepository<RateCardEvent, Long> {
    List<RateCardEvent> findByRateCardIdOrderByIdAsc(Long rateCardId);
}
