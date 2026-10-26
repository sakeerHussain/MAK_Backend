package com.vehiclerental.mak.fleet.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.controller.VehicleImportDraftController;
import com.vehiclerental.mak.fleet.dto.request.*;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.fleet.service.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@SpringBootTest
class VehicleImportDraftTests {
    @Autowired VehicleImportDraftService drafts;
    @Autowired VehicleService vehicles;
    @Autowired VehicleRepository repository;
    @Autowired ObjectMapper json;

    private VehicleRequest partial() {
        String id = UUID.randomUUID().toString();
        var data = new HashMap<String,Object>();
        data.put("make", "Test maker"); data.put("model", "Test model");
        data.put("registration", "DR-" + id); data.put("vehicleId", "DV-" + id);
        data.put("rateCurrency", "SAR"); data.put("insuranceExpiryDate", "2020-07-31");
        return json.convertValue(data, VehicleRequest.class);
    }
    private VehicleRequest full(VehicleRequest partial) {
        var data = json.convertValue(partial, new TypeReference<HashMap<String,Object>>() {});
        data.putAll(Map.of("year", 2026, "type", "Bus", "seats", 35, "transmission", "Manual", "fuelType", "Diesel", "dailyRate", 500, "ownership", "Owned", "onSite", true));
        data.put("status", "Inactive"); data.put("listedForBooking", false); data.put("showOnPublicSite", false);
        return json.convertValue(data, VehicleRequest.class);
    }

    @Test void preservesUnknownValuesAndDatesWithoutCreatingBookableVehicles() {
        var input = partial(); long total = vehicles.summary().total();
        var saved = drafts.save(null, new VehicleImportDraftRequest(input, null));
        var found = drafts.list().stream().filter(row -> row.id() == saved.id()).findFirst().orElseThrow();
        assertNull(found.details().year()); assertNull(found.details().type()); assertNull(found.details().seats());
        assertNull(found.details().transmission()); assertNull(found.details().fuelType()); assertNull(found.details().dailyRate());
        assertNull(found.details().ownership()); assertNull(found.details().onSite());
        assertEquals("Inactive", found.details().status()); assertFalse(found.details().listedForBooking()); assertFalse(found.details().showOnPublicSite());
        assertEquals(LocalDate.of(2020, 7, 31), found.details().insuranceExpiryDate());
        assertTrue(found.missingFields().containsAll(List.of("year", "type", "seats", "transmission", "fuelType", "dailyRate", "ownership", "onSite")));
        assertEquals(total, vehicles.summary().total());
        assertFalse(repository.existsByVehicleIdIgnoreCaseAndIdNot(input.vehicleId(), -1L));
        assertEquals(0, vehicles.list(input.registration(), null, null, null, 0, 20).getTotalElements());
    }

    @Test void partialProgressPersistsAndStaleVersionsCannotOverwriteOrComplete() {
        var saved = drafts.save(null, new VehicleImportDraftRequest(partial(), null));
        var data = json.convertValue(saved.details(), new TypeReference<HashMap<String,Object>>() {});
        data.put("seats", 35);
        var updated = drafts.save(saved.id(), new VehicleImportDraftRequest(json.convertValue(data, VehicleRequest.class), saved.version()));
        assertTrue(updated.version() > saved.version()); assertEquals(35, updated.details().seats()); assertNull(updated.details().dailyRate());
        assertThrows(ApiException.class, () -> drafts.save(saved.id(), new VehicleImportDraftRequest(saved.details(), saved.version())));
        assertThrows(ApiException.class, () -> drafts.complete(saved.id(), new VehicleDraftCompletionRequest(full(saved.details()), saved.version())));
        assertThrows(ApiException.class, () -> drafts.discard(saved.id(), saved.version()));
        assertTrue(drafts.list().stream().anyMatch(row -> row.id() == saved.id() && row.details().seats() == 35));
    }

    @Test void incompleteCompletionFailsAndCompletePromotionIsAtomicAndCannotRepeat() {
        var saved = drafts.save(null, new VehicleImportDraftRequest(partial(), null));
        assertThrows(ApiException.class, () -> drafts.complete(saved.id(), new VehicleDraftCompletionRequest(saved.details(), saved.version())));
        assertTrue(drafts.list().stream().anyMatch(row -> row.id() == saved.id()));
        var completed = drafts.complete(saved.id(), new VehicleDraftCompletionRequest(full(saved.details()), saved.version()));
        assertEquals("Bus", completed.details().type()); assertEquals(35, completed.details().seats());
        assertEquals("SAR", completed.details().rateCurrency()); assertEquals(LocalDate.of(2020,7,31), completed.details().insuranceExpiryDate());
        assertTrue(drafts.list().stream().noneMatch(row -> row.id() == saved.id()));
        assertThrows(ApiException.class, () -> drafts.complete(saved.id(), new VehicleDraftCompletionRequest(full(saved.details()), saved.version())));
        assertEquals(1, vehicles.list(saved.details().vehicleId(), null, null, null, 0, 20).getTotalElements());
    }

    @Test void duplicateAndInvalidRowsFailIndependentlyAndDiscardDoesNotChangeFleet() {
        var input = partial(); var data = json.convertValue(input, new TypeReference<HashMap<String,Object>>() {});
        data.put("registration", "BAD-"+UUID.randomUUID()); data.put("vehicleId", "BAD-"+UUID.randomUUID()); data.put("dailyRate", 0);
        var result = new VehicleImportDraftController(drafts).saveRows(Arrays.asList(new VehicleImportDraftRequest(input,null), new VehicleImportDraftRequest(input,null), new VehicleImportDraftRequest(json.convertValue(data,VehicleRequest.class),null), null));
        assertEquals(1, result.successful()); assertEquals(3, result.failed()); assertTrue(result.rows().get(2).error().contains("dailyRate"));
        var saved = drafts.list().stream().filter(row -> row.id() == result.rows().getFirst().id()).findFirst().orElseThrow();
        long total = vehicles.summary().total(); drafts.discard(saved.id(), saved.version());
        assertEquals(total, vehicles.summary().total());
        assertTrue(drafts.list().stream().noneMatch(row -> row.id() == saved.id()));
    }

    @Test void identityIsMandatoryAndExistingFleetDuplicatesCannotBecomeDrafts() {
        assertThrows(ApiException.class, () -> drafts.save(null, new VehicleImportDraftRequest(json.convertValue(Map.of("make","Test"),VehicleRequest.class),null)));
        var input = partial(); vehicles.create(full(input));
        assertThrows(ApiException.class, () -> drafts.save(null, new VehicleImportDraftRequest(input,null)));
    }
}
