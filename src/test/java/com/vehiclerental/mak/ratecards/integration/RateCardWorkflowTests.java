package com.vehiclerental.mak.ratecards.integration;

import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.repository.AuthControlRepository;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.service.AccountService;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.ratecards.dto.RateCardRequest;
import com.vehiclerental.mak.ratecards.repository.RateCardRepository;
import com.vehiclerental.mak.ratecards.service.PricingService;
import com.vehiclerental.mak.ratecards.service.RateCardService;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @Transactional
class RateCardWorkflowTests {
    @Autowired RateCardService service; @Autowired PricingService pricing; @Autowired RateCardRepository cards;
    @Autowired VehicleRepository vehicles; @Autowired AccountRepository accounts; @Autowired AccountService accountService;
    @Autowired AuthControlRepository controls; @Autowired WebApplicationContext context;
    Account finance; MockMvc mvc;
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Riyadh"));

    @BeforeEach void setup() {
        controls.findById(1).orElseThrow().setBootstrapDone(true);
        cards.deleteAll(); cards.flush();
        vehicles.findAll().forEach(v -> { v.setListedForBooking(false); vehicles.save(v); });
        finance = account("Finance");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    Account account(String role) {
        var a = new Account(); a.setName(role + " QA"); a.setEmail("rc-" + UUID.randomUUID() + "@example.invalid"); a.setPasswordHash("x");
        a.setRole(role); a.setStatus("Active"); a.setCreatedAt(Instant.now()); return accounts.saveAndFlush(a);
    }
    MockHttpSession session(Account a) {
        var s = new MockHttpSession();
        s.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(a.getId(), a.getSessionVersion()),
                null, accountService.permissions(a).stream().map(SimpleGrantedAuthority::new).toList())));
        return s;
    }
    Vehicle bookable(String type) {
        var v = new Vehicle(); String key = UUID.randomUUID().toString();
        v.setRegistration("RC-" + key); v.setVehicleId("RC-" + key); v.setMake("QA"); v.setModel(type); v.setYear(2026); v.setType(type);
        v.setSeats(5); v.setTransmission("Automatic"); v.setFuelType("Petrol"); v.setOwnership("Owned"); v.setStatus("Available");
        v.setOnSite(true); v.setListedForBooking(true); return vehicles.saveAndFlush(v);
    }
    static RateCardRequest.Line line(String type, Long vehicle, String unit, String amount) { return new RateCardRequest.Line(type, vehicle, unit, new BigDecimal(amount)); }
    RateCardRequest request(LocalDate from, Long version, List<RateCardRequest.Line> lines) {
        return new RateCardRequest("Standard rates", from, null, new BigDecimal("15.00"), 30, null, null, null, null, lines, version);
    }
    @Test void publishRequiresDailyRateForEveryBookableClassAndThenLocksTerms() {
        bookable("SUV"); bookable("Van");
        var draft = service.create(request(today, null, List.of(line("SUV", null, "DAY", "400"))), finance.getId());
        assertEquals(List.of("Van"), draft.coverage().missingDayRate());
        var blocked = assertThrows(ApiException.class, () -> service.publish(draft.id(), draft.version(), finance.getId()));
        assertTrue(blocked.getMessage().contains("Van"));
        var fixed = service.update(draft.id(), request(today, draft.version(), List.of(line("SUV", null, "DAY", "400"), line("Van", null, "DAY", "650"))), finance.getId());
        var published = service.publish(fixed.id(), fixed.version(), finance.getId());
        assertEquals("PUBLISHED", published.status()); assertEquals(64, published.digest().length());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.update(published.id(),
                request(today, published.version(), List.of(line("SUV", null, "DAY", "1"))), finance.getId())).status());
        assertTrue(service.get(published.id()).history().stream().anyMatch(e -> "PUBLISHED".equals(e.action())));
    }

    @Test void revisionSucceedsPreviousCardWithoutOverlapAndPricingFollowsTheDate() {
        bookable("SUV");
        var first = service.create(request(today, null, List.of(line("SUV", null, "DAY", "400"))), finance.getId());
        var live = service.publish(first.id(), first.version(), finance.getId());
        var next = service.revise(live.id(), finance.getId());
        assertEquals("DRAFT", next.status()); assertEquals(live.id(), next.revisedFromId());
        var changed = service.update(next.id(), request(today.plusDays(10), next.version(), List.of(line("SUV", null, "DAY", "450"))), finance.getId());
        service.publish(changed.id(), changed.version(), finance.getId());
        assertEquals(today.plusDays(9), service.get(live.id()).effectiveTo());
        var before = pricing.quote(new PricingService.Request("SUV", null, "DAY", today.atTime(9, 0), today.atTime(9, 0).plusDays(1)));
        var after = pricing.quote(new PricingService.Request("SUV", null, "DAY", today.plusDays(10).atTime(9, 0), today.plusDays(11).atTime(9, 0)));
        assertEquals(0, new BigDecimal("400.00").compareTo(before.unitPrice()));
        assertEquals(0, new BigDecimal("450.00").compareTo(after.unitPrice()));
        var stale = service.revise(live.id(), finance.getId());
        assertThrows(ApiException.class, () -> service.publish(stale.id(), stale.version(), finance.getId()), "cannot start on or before the latest revision");
    }

    @Test void validationRejectsDuplicatesWrongClassOverridesAndPastStarts() {
        var suv = bookable("SUV");
        assertThrows(ApiException.class, () -> service.create(request(today, null, List.of(line("SUV", null, "DAY", "1"), line("SUV", null, "DAY", "2"))), finance.getId()));
        assertThrows(ApiException.class, () -> service.create(request(today, null, List.of(line("Van", suv.getId(), "DAY", "1"))), finance.getId()));
        var past = service.create(request(today.minusDays(1), null, List.of(line("SUV", null, "DAY", "400"))), finance.getId());
        assertThrows(ApiException.class, () -> service.publish(past.id(), past.version(), finance.getId()));
        var override = service.create(request(today, null, List.of(line("SUV", null, "DAY", "400"), line("SUV", suv.getId(), "DAY", "500"))), finance.getId());
        assertEquals(suv.getRegistration() + " · QA SUV", override.lines().stream().filter(l -> l.vehicleId() != null).findFirst().orElseThrow().vehicleLabel());
        service.discard(override.id(), override.version());
        assertTrue(cards.findById(override.id()).isEmpty());
    }

    @Test void onlyPricingRolesCanReadAndOnlyPricingWritersCanChange() throws Exception {
        String body = "{\"name\":\"Rates\",\"effectiveFrom\":\"" + today + "\",\"taxPercent\":15,\"graceMinutes\":30,\"lines\":[]}";
        var operations = session(account("Operations"));
        assertEquals(403, mvc.perform(get("/api/rate-cards").session(operations)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(post("/api/rate-cards").session(operations).with(csrf().asHeader()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        var financeSession = session(finance);
        assertEquals(200, mvc.perform(get("/api/rate-cards").session(financeSession)).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(post("/api/rate-cards").session(financeSession).with(csrf().asHeader()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/rate-cards")).andReturn().getResponse().getStatus());
    }
}
