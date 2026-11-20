package com.vehiclerental.mak.ratecards.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.ratecards.dto.RateCardRequest;
import com.vehiclerental.mak.ratecards.dto.RateCardResponse;
import com.vehiclerental.mak.ratecards.entity.RateCard;
import com.vehiclerental.mak.ratecards.entity.RateCardEvent;
import com.vehiclerental.mak.ratecards.entity.RateCardLine;
import com.vehiclerental.mak.ratecards.repository.RateCardEventRepository;
import com.vehiclerental.mak.ratecards.repository.RateCardRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Standard rate card lifecycle: editable drafts, immutable published revisions, effective-dated succession. */
@Service
public class RateCardService {
    static final ZoneId KSA = ZoneId.of("Asia/Riyadh");
    static final String DRAFT = "DRAFT", PUBLISHED = "PUBLISHED";
    private final RateCardRepository cards;
    private final RateCardEventRepository events;
    private final VehicleRepository vehicles;
    private final RateCardViews views;

    public RateCardService(RateCardRepository cards, RateCardEventRepository events, VehicleRepository vehicles, RateCardViews views) {
        this.cards = cards; this.events = events; this.vehicles = vehicles; this.views = views;
    }

    @Transactional(readOnly = true)
    public List<RateCardResponse> list() { return cards.findAllByOrderByRevisionDesc().stream().map(card -> views.response(card, false)).toList(); }

    @Transactional(readOnly = true)
    public RateCardResponse get(long id) { return views.response(require(id), true); }

    @Transactional
    public RateCardResponse create(RateCardRequest request, long actor) {
        var card = new RateCard();
        card.setRevision(cards.maxRevision() + 1); card.setStatus(DRAFT);
        card.setCreatedBy(actor); card.setCreatedAt(Instant.now());
        apply(request, card);
        cards.saveAndFlush(card);
        event(card, actor, "CREATED", null);
        return views.response(card, true);
    }

    @Transactional
    public RateCardResponse update(long id, RateCardRequest request, long actor) {
        var card = lockDraft(id, request.version());
        apply(request, card);
        cards.saveAndFlush(card);
        event(card, actor, "UPDATED", null);
        return views.response(card, true);
    }

    /** Start a new draft from a published revision. Published terms themselves never change. */
    @Transactional
    public RateCardResponse revise(long id, long actor) {
        var source = require(id);
        if (!PUBLISHED.equals(source.getStatus())) throw conflict("Only a published rate card can be revised. Edit the draft instead.");
        var card = new RateCard();
        card.setRevision(cards.maxRevision() + 1); card.setStatus(DRAFT); card.setRevisedFromId(source.getId());
        card.setName(source.getName()); card.setTaxPercent(source.getTaxPercent()); card.setGraceMinutes(source.getGraceMinutes());
        card.setExtraKmRate(source.getExtraKmRate()); card.setOvertimeHourRate(source.getOvertimeHourRate());
        card.setExtraDriverRate(source.getExtraDriverRate()); card.setNotes(source.getNotes());
        LocalDate tomorrow = LocalDate.now(KSA).plusDays(1), next = source.getEffectiveFrom().plusDays(1);
        card.setEffectiveFrom(next.isAfter(tomorrow) ? next : tomorrow);
        for (var line : source.getLines()) card.getLines().add(line(card, line.getVehicleClass(), line.getVehicleId(), line.getUnit(), line.getAmount()));
        card.setCreatedBy(actor); card.setCreatedAt(Instant.now()); card.setUpdatedAt(Instant.now());
        cards.saveAndFlush(card);
        event(card, actor, "REVISED", "From revision " + source.getRevision());
        return views.response(card, true);
    }

    /** Publishing locks the terms and ends the previous revision the day before this one starts. */
    @Transactional
    public RateCardResponse publish(long id, Long version, long actor) {
        var card = lockDraft(id, version);
        if (card.getLines().isEmpty()) throw bad("Add at least one rate before publishing.");
        var coverage = views.coverage(card);
        if (!coverage.missingDayRate().isEmpty())
            throw bad("Add a daily rate for every bookable vehicle class before publishing: " + String.join(", ", coverage.missingDayRate()) + ".");
        if (card.getEffectiveFrom().isBefore(LocalDate.now(KSA))) throw bad("A rate card cannot take effect in the past. Choose today or a later date.");
        for (var previous : cards.lockPublished()) {
            if (!previous.getEffectiveFrom().isBefore(card.getEffectiveFrom()))
                throw conflict("Revision " + previous.getRevision() + " already takes effect on " + previous.getEffectiveFrom() + ". Choose a later effective date.");
            if (previous.getEffectiveTo() == null || !previous.getEffectiveTo().isBefore(card.getEffectiveFrom()))
                previous.setEffectiveTo(card.getEffectiveFrom().minusDays(1));
        }
        card.setStatus(PUBLISHED); card.setDigest(RateCardViews.digest(card));
        card.setPublishedBy(actor); card.setPublishedAt(Instant.now()); card.setUpdatedAt(Instant.now());
        cards.saveAndFlush(card);
        event(card, actor, "PUBLISHED", null);
        return views.response(card, true);
    }

    @Transactional
    public void discard(long id, Long version) { cards.delete(lockDraft(id, version)); cards.flush(); }

    private void apply(RateCardRequest r, RateCard card) {
        if (r.effectiveTo() != null && r.effectiveTo().isBefore(r.effectiveFrom())) throw bad("End date cannot be before the effective date.");
        var seen = new HashSet<String>();
        var overrideIds = r.lines().stream().map(RateCardRequest.Line::vehicleId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Vehicle> found = new HashMap<>();
        vehicles.findAllById(overrideIds).forEach(vehicle -> found.put(vehicle.getId(), vehicle));
        for (var line : r.lines()) {
            if (!seen.add(line.vehicleClass() + "|" + line.vehicleId() + "|" + line.unit()))
                throw bad("Each class or vehicle can have only one " + line.unit().toLowerCase(Locale.ROOT) + " rate.");
            if (line.vehicleId() != null) {
                var vehicle = found.get(line.vehicleId());
                if (vehicle == null) throw bad("A vehicle override refers to a vehicle that no longer exists.");
                if (!line.vehicleClass().equals(vehicle.getType()))
                    throw bad("Vehicle " + vehicle.getRegistration() + " is a " + vehicle.getType() + ", not a " + line.vehicleClass() + ".");
            }
        }
        card.setName(r.name().trim()); card.setEffectiveFrom(r.effectiveFrom()); card.setEffectiveTo(r.effectiveTo());
        card.setTaxPercent(r.taxPercent()); card.setGraceMinutes(r.graceMinutes());
        card.setExtraKmRate(r.extraKmRate()); card.setOvertimeHourRate(r.overtimeHourRate()); card.setExtraDriverRate(r.extraDriverRate());
        card.setNotes(r.notes() == null || r.notes().isBlank() ? null : r.notes().trim());
        card.getLines().clear();
        for (var line : r.lines()) card.getLines().add(line(card, line.vehicleClass(), line.vehicleId(), line.unit(), line.amount()));
        card.setUpdatedAt(Instant.now());
    }

    private RateCardLine line(RateCard card, String vehicleClass, Long vehicleId, String unit, BigDecimal amount) {
        var line = new RateCardLine();
        line.setCard(card); line.setVehicleClass(vehicleClass); line.setVehicleId(vehicleId); line.setUnit(unit); line.setAmount(amount);
        return line;
    }
    private RateCard lockDraft(long id, Long version) {
        var card = cards.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rate card not found."));
        if (!DRAFT.equals(card.getStatus())) throw conflict("Published rate cards are locked. Create a revision to change prices.");
        if (version == null || version != card.getVersion()) throw conflict("This rate card changed. Reload it before saving.");
        return card;
    }
    private RateCard require(long id) { return cards.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rate card not found.")); }
    private void event(RateCard card, long actor, String action, String reason) {
        var event = new RateCardEvent();
        event.setRateCardId(card.getId()); event.setActorId(actor); event.setAction(action); event.setReason(reason); event.setCreatedAt(Instant.now());
        events.save(event);
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}
