package com.vehiclerental.mak.ratecards.controller;

import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.ratecards.dto.RateCardRequest;
import com.vehiclerental.mak.ratecards.dto.RateCardResponse;
import com.vehiclerental.mak.ratecards.dto.RateQuote;
import com.vehiclerental.mak.ratecards.service.PricingService;
import com.vehiclerental.mak.ratecards.service.RateCardService;

import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Reads need pricing.read; every change needs pricing.write (enforced in SecurityConfig). */
@RestController @RequestMapping("/api/rate-cards")
public class RateCardController {
    private final RateCardService cards;
    private final PricingService pricing;
    public RateCardController(RateCardService cards, PricingService pricing) { this.cards = cards; this.pricing = pricing; }

    @GetMapping public List<RateCardResponse> list() { return cards.list(); }
    @GetMapping("/{id}") public RateCardResponse get(@PathVariable long id) { return cards.get(id); }
    @PostMapping public RateCardResponse create(@Valid @RequestBody RateCardRequest request, Authentication auth) { return cards.create(request, actor(auth)); }
    @PutMapping("/{id}") public RateCardResponse update(@PathVariable long id, @Valid @RequestBody RateCardRequest request, Authentication auth) { return cards.update(id, request, actor(auth)); }
    @PostMapping("/{id}/publish") public RateCardResponse publish(@PathVariable long id, @RequestParam Long version, Authentication auth) { return cards.publish(id, version, actor(auth)); }
    @PostMapping("/{id}/revise") public RateCardResponse revise(@PathVariable long id, Authentication auth) { return cards.revise(id, actor(auth)); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> discard(@PathVariable long id, @RequestParam Long version) { cards.discard(id, version); return ResponseEntity.noContent().build(); }

    /** Finance preview: what the active card would charge. Bookings use the same service in Phase 3. */
    @GetMapping("/quote") public RateQuote quote(@RequestParam String vehicleClass, @RequestParam(required = false) Long vehicleId,
            @RequestParam(defaultValue = "DAY") String unit, @RequestParam LocalDateTime pickupAt, @RequestParam LocalDateTime returnAt) {
        return pricing.quote(new PricingService.Request(vehicleClass, vehicleId, unit, pickupAt, returnAt));
    }

    private long actor(Authentication auth) { return ((AccountPrincipal) auth.getPrincipal()).id(); }
}
