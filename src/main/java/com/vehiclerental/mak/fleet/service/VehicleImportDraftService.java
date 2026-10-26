package com.vehiclerental.mak.fleet.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.dto.request.*;
import com.vehiclerental.mak.fleet.dto.response.*;
import com.vehiclerental.mak.fleet.entity.VehicleImportDraft;
import com.vehiclerental.mak.fleet.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import jakarta.validation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Service
public class VehicleImportDraftService {
    private static final Set<String> OPTIONAL = Set.of("year", "type", "seats", "transmission", "fuelType", "dailyRate", "ownership", "onSite");
    private final VehicleImportDraftRepository drafts;
    private final VehicleRepository fleet;
    private final VehicleService vehicles;
    private final ObjectMapper json;
    private final Validator validator;

    public VehicleImportDraftService(VehicleImportDraftRepository drafts, VehicleRepository fleet, VehicleService vehicles, ObjectMapper json, Validator validator) {
        this.drafts = drafts; this.fleet = fleet; this.vehicles = vehicles; this.json = json; this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<VehicleImportDraftResponse> list() { return drafts.findAllByOrderByIdDesc().stream().map(this::response).toList(); }

    @Transactional
    public VehicleImportDraftResponse save(Long id, VehicleImportDraftRequest request) {
        if (request == null || request.details() == null) throw bad("Vehicle draft details are required.");
        var draft = id == null ? new VehicleImportDraft() : locked(id, request.version());
        // Always keep drafts inactive and unlisted. Never apply ordinary form defaults to unknown fields.
        var data = json.convertValue(request.details(), new TypeReference<LinkedHashMap<String,Object>>() {});
        data.replaceAll((key, value) -> value instanceof String text ? text.isBlank() ? null : text.trim() : value);
        for (String key : List.of("registration", "vehicleId")) if (data.get(key) instanceof String text) data.put(key, text.toUpperCase(Locale.ROOT));
        data.put("status", "Inactive"); data.put("listedForBooking", false); data.put("showOnPublicSite", false);
        var details = json.convertValue(data, VehicleRequest.class);
        String invalid = validator.validate(details).stream().filter(v -> !allowedMissing(v)).map(this::message).sorted().collect(Collectors.joining("; "));
        if (!invalid.isEmpty()) throw bad(invalid);
        long excluded = id == null ? -1 : id;
        if (fleet.existsByRegistrationIgnoreCaseAndIdNot(details.registration(), -1L) || fleet.existsByVehicleIdIgnoreCaseAndIdNot(details.vehicleId(), -1L))
            throw conflict("This registration number or vehicle ID is already in Fleet.");
        if (drafts.existsByRegistrationAndIdNot(details.registration(), excluded) || drafts.existsByVehicleIdAndIdNot(details.vehicleId(), excluded))
            throw conflict("An import draft with this registration number or vehicle ID already exists. Complete or discard the existing draft.");
        draft.setRegistration(details.registration()); draft.setVehicleId(details.vehicleId());
        draft.setPayload(json.writeValueAsString(details));
        if (id == null) draft.setCreatedAt(Instant.now());
        drafts.saveAndFlush(draft);
        return response(draft);
    }

    @Transactional
    public VehicleResponse complete(long id, VehicleDraftCompletionRequest request) {
        if (request == null || request.details() == null) throw bad("Complete the vehicle details first.");
        var draft = locked(id, request.version());
        String invalid = validator.validate(request.details()).stream().map(this::message).sorted().collect(Collectors.joining("; "));
        if (!invalid.isEmpty()) throw bad(invalid);
        var created = vehicles.create(request.details());
        drafts.delete(draft); drafts.flush();
        return created;
    }

    @Transactional
    public void discard(long id, long version) { drafts.delete(locked(id, version)); drafts.flush(); }

    private VehicleImportDraft locked(long id, Long version) {
        var draft = drafts.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Import draft not found. It may already have been completed or discarded."));
        if (version == null || version != draft.getVersion()) throw conflict("This import draft changed. Close it and reload the drafts before trying again.");
        return draft;
    }

    private boolean allowedMissing(ConstraintViolation<VehicleRequest> violation) {
        var annotation = violation.getConstraintDescriptor().getAnnotation().annotationType();
        return OPTIONAL.contains(violation.getPropertyPath().toString()) && (annotation == NotNull.class || annotation == NotBlank.class);
    }

    private VehicleImportDraftResponse response(VehicleImportDraft draft) {
        var details = json.readValue(draft.getPayload(), VehicleRequest.class);
        var missing = new TreeSet<>(validator.validate(details).stream().map(v -> v.getPropertyPath().toString()).toList());
        if ("Vendor".equals(details.ownership()) && (details.vendor() == null || details.vendor().isBlank())) missing.add("vendor");
        return new VehicleImportDraftResponse(draft.getId(), draft.getVersion(), details, List.copyOf(missing), draft.getCreatedAt());
    }

    private String message(ConstraintViolation<VehicleRequest> violation) { return violation.getPropertyPath() + ": " + violation.getMessage(); }
    private ApiException bad(String text) { return new ApiException(HttpStatus.BAD_REQUEST, text); }
    private ApiException conflict(String text) { return new ApiException(HttpStatus.CONFLICT, text); }
}
