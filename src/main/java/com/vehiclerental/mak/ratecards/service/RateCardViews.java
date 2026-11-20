package com.vehiclerental.mak.ratecards.service;

import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.ratecards.dto.RateCardResponse;
import com.vehiclerental.mak.ratecards.entity.RateCard;
import com.vehiclerental.mak.ratecards.repository.RateCardEventRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

/** Read models for rate cards: responses, publish coverage and the terms fingerprint. */
@Component
public class RateCardViews {
    private final VehicleRepository vehicles;
    private final RateCardEventRepository events;
    private final AccountRepository accounts;

    public RateCardViews(VehicleRepository vehicles, RateCardEventRepository events, AccountRepository accounts) {
        this.vehicles = vehicles; this.events = events; this.accounts = accounts;
    }

    public RateCardResponse response(RateCard card, boolean detail) {
        var overrideIds = card.getLines().stream().map(line -> line.getVehicleId()).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> labels = new HashMap<>();
        vehicles.findAllById(overrideIds).forEach(v -> labels.put(v.getId(), v.getRegistration() + " · " + v.getMake() + " " + v.getModel()));
        var lines = card.getLines().stream().map(line -> new RateCardResponse.Line(line.getId(), line.getVehicleClass(), line.getVehicleId(),
                line.getVehicleId() == null ? null : labels.getOrDefault(line.getVehicleId(), "Removed vehicle"), line.getUnit(), line.getAmount())).toList();
        List<RateCardResponse.Event> history = detail ? events.findByRateCardIdOrderByIdAsc(card.getId()).stream()
                .map(e -> new RateCardResponse.Event(e.getAction(), name(e.getActorId()), e.getReason(), e.getCreatedAt())).toList() : List.of();
        return new RateCardResponse(card.getId(), card.getName(), card.getRevision(), card.getStatus(), card.getEffectiveFrom(),
                card.getEffectiveTo(), card.getTaxPercent(), card.getGraceMinutes(), card.getExtraKmRate(), card.getOvertimeHourRate(),
                card.getExtraDriverRate(), card.getNotes(), card.getRevisedFromId(), card.getDigest(), card.getCreatedAt(),
                card.getUpdatedAt(), card.getPublishedAt(), card.getPublishedBy() == null ? null : name(card.getPublishedBy()),
                card.getVersion(), lines, coverage(card), history);
    }

    /** Every class with a bookable vehicle needs a class-level DAY rate so quick bookings can always be priced. */
    public RateCardResponse.Coverage coverage(RateCard card) {
        var bookable = new TreeSet<>(vehicles.bookableTypes());
        var priced = new HashSet<String>();
        card.getLines().stream().filter(line -> line.getVehicleId() == null && "DAY".equals(line.getUnit()))
                .forEach(line -> priced.add(line.getVehicleClass()));
        var missing = bookable.stream().filter(type -> !priced.contains(type)).toList();
        return new RateCardResponse.Coverage(List.copyOf(bookable), missing, missing.isEmpty() && !card.getLines().isEmpty());
    }

    /** Stable SHA-256 of the priced terms, recorded at publish time so later snapshots can prove which terms applied. */
    public static String digest(RateCard card) {
        var text = new StringBuilder();
        text.append(card.getRevision()).append('|').append(card.getEffectiveFrom()).append('|').append(plain(card.getTaxPercent()))
                .append('|').append(card.getGraceMinutes()).append('|').append(plain(card.getExtraKmRate())).append('|')
                .append(plain(card.getOvertimeHourRate())).append('|').append(plain(card.getExtraDriverRate()));
        card.getLines().stream().map(line -> line.getVehicleClass() + ":" + line.getVehicleId() + ":" + line.getUnit() + ":" + plain(line.getAmount()))
                .sorted().forEach(line -> text.append('|').append(line));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String plain(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    private String name(Long id) { return id == null ? null : accounts.findById(id).map(a -> a.getName()).orElse("Former user"); }
}
