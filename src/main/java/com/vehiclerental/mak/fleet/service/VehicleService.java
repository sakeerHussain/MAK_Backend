package com.vehiclerental.mak.fleet.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.dto.request.VehicleRequest;
import com.vehiclerental.mak.fleet.dto.request.VehicleStatusRequest;
import com.vehiclerental.mak.fleet.dto.response.VehicleFileResponse;
import com.vehiclerental.mak.fleet.dto.response.VehicleResponse;
import com.vehiclerental.mak.fleet.dto.response.VehicleSummary;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.entity.VehicleFile;
import com.vehiclerental.mak.fleet.mapper.VehicleFileMapper;
import com.vehiclerental.mak.fleet.repository.VehicleFileRepository;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.maintenance.repository.MaintenanceRepository;
import com.vehiclerental.mak.rentals.repository.RentalRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {
    private static final Set<String> REQUIRED_DOCUMENTS = Set.of("Istimara", "Insurance", "Fahas");
    private final VehicleRepository vehicles;
    private final VehicleFileRepository files;
    private final MaintenanceRepository maintenance;
    private final RentalRepository rentals;

    public VehicleService(VehicleRepository vehicles, VehicleFileRepository files, MaintenanceRepository maintenance, RentalRepository rentals) {
        this.vehicles = vehicles;
        this.files = files; this.maintenance = maintenance; this.rentals = rentals;
    }

    @Transactional(readOnly = true)
    public Page<VehicleResponse> list(String search, String status, String type, Boolean onSite, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be 0 or greater and size must be between 1 and 100.");
        Specification<Vehicle> query = (root, ignored, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                parts.add(cb.or(cb.like(cb.lower(root.get("registration")), term),
                        cb.like(cb.lower(root.get("vehicleId")), term),
                        cb.like(cb.lower(root.get("make")), term), cb.like(cb.lower(root.get("model")), term)));
            }
            if (status != null && !status.isBlank()) parts.add(cb.equal(root.get("status"), status));
            if (type != null && !type.isBlank()) parts.add(cb.equal(root.get("type"), type));
            if (onSite != null) parts.add(cb.equal(root.get("onSite"), onSite));
            return cb.and(parts.toArray(Predicate[]::new));
        };
        Page<Vehicle> result = vehicles.findAll(query, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, List<VehicleFile>> byVehicle = new HashMap<>();
        if (!result.isEmpty()) {
            files.findByVehicleIdInOrderByIdAsc(result.getContent().stream().map(Vehicle::getId).toList())
                    .forEach(file -> byVehicle.computeIfAbsent(file.getVehicle().getId(), unused -> new ArrayList<>()).add(file));
        }
        return result.map(vehicle -> response(vehicle, byVehicle.getOrDefault(vehicle.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public VehicleResponse get(long id) {
        Vehicle vehicle = require(id);
        return response(vehicle, files.findByVehicleIdOrderByIdAsc(id));
    }

    @Transactional(readOnly = true)
    public VehicleSummary summary() {
        return new VehicleSummary(vehicles.count(), vehicles.countByStatus("Available"),
                vehicles.countByStatus("In Use"), vehicles.countByStatus("Under Maintenance"), vehicles.countByStatus("Inactive"));
    }

    @Transactional
    public VehicleResponse create(VehicleRequest request) {
        validateBusinessRules(request, null);
        Vehicle vehicle = new Vehicle();
        copy(request, vehicle);
        vehicles.saveAndFlush(vehicle);
        return response(vehicle, List.of());
    }

    @Transactional
    public VehicleResponse update(long id, VehicleRequest request) {
        Vehicle vehicle = locked(id);
        guardStatus(id, request.status());
        validateBusinessRules(request, id);
        copy(request, vehicle);
        vehicles.flush();
        return response(vehicle, files.findByVehicleIdOrderByIdAsc(id));
    }

    @Transactional
    public VehicleResponse updateStatus(long id, VehicleStatusRequest request) {
        Vehicle vehicle = locked(id);
        guardStatus(id, request.status());
        vehicle.setStatus(request.status());
        return response(vehicle, files.findByVehicleIdOrderByIdAsc(id));
    }

    private Vehicle locked(long id) { return vehicles.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"Vehicle not found.")); }
    private void guardStatus(long id,String status) {
        if (maintenance.activeCount(id)>0 && !"Under Maintenance".equals(status)) throw new ApiException(HttpStatus.CONFLICT,"Close the active maintenance jobs before changing availability.");
        if (rentals.countByVehicleIdAndStatus(id,"Active")>0 && !"In Use".equals(status)) throw new ApiException(HttpStatus.CONFLICT,"Return the active rental before changing availability.");
    }
    Vehicle require(long id) {
        return vehicles.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Vehicle not found."));
    }

    private void validateBusinessRules(VehicleRequest request, Long id) {
        Long excluded = id == null ? -1L : id;
        if (request.year() > LocalDate.now().getYear() + 1) throw new ApiException(HttpStatus.BAD_REQUEST, "Year of registration cannot be in the distant future.");
        if (vehicles.existsByRegistrationIgnoreCaseAndIdNot(request.registration().trim(), excluded)) throw new ApiException(HttpStatus.CONFLICT, "Registration number already exists.");
        if (vehicles.existsByVehicleIdIgnoreCaseAndIdNot(request.vehicleId().trim(), excluded)) throw new ApiException(HttpStatus.CONFLICT, "Vehicle ID already exists.");
        if ("Vendor".equals(request.ownership()) && (request.vendor() == null || request.vendor().isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST, "Vendor is required for vendor-owned vehicles.");
        validateSchedule("Service", request.serviceIntervalKm(), request.lastServiceKm(), request.currentOdometerKm());
        validateSchedule("Oil", request.oilIntervalKm(), request.lastOilKm(), request.currentOdometerKm());
        validateSchedule("Tyres", request.tyreIntervalKm(), request.lastTyreKm(), request.currentOdometerKm());
        validateSchedule("Brakes", request.brakeIntervalKm(), request.lastBrakeKm(), request.currentOdometerKm());
    }

    private void validateSchedule(String name, Long interval, Long last, Long current) {
        if (interval != null && (last == null || current == null)) throw new ApiException(HttpStatus.BAD_REQUEST, name + " interval needs a last reading and current odometer.");
        if (last != null && current == null) throw new ApiException(HttpStatus.BAD_REQUEST, name + " last reading needs a current odometer.");
        if (last != null && current != null && last > current) throw new ApiException(HttpStatus.BAD_REQUEST, name + " last reading cannot exceed the current odometer.");
    }

    private void copy(VehicleRequest request, Vehicle vehicle) {
        vehicle.setMake(request.make().trim());
        vehicle.setModel(request.model().trim());
        vehicle.setRegistration(request.registration().trim().toUpperCase(Locale.ROOT));
        vehicle.setVehicleId(request.vehicleId().trim().toUpperCase(Locale.ROOT));
        vehicle.setYear(request.year());
        vehicle.setColor(trim(request.color()));
        vehicle.setDoorNo(trim(request.doorNo()));
        vehicle.setChassisNo(trim(request.chassisNo()));
        vehicle.setType(request.type());
        vehicle.setSeats(request.seats());
        vehicle.setTransmission(request.transmission());
        vehicle.setFuelType(request.fuelType());
        vehicle.setFeatureTags(request.featureTags() == null ? new ArrayList<>() : request.featureTags().stream().map(String::trim).filter(tag -> !tag.isEmpty()).distinct().collect(Collectors.toCollection(ArrayList::new)));
        vehicle.setHourlyRate(request.hourlyRate());
        vehicle.setDailyRate(request.dailyRate());
        vehicle.setMonthlyRate(request.monthlyRate());
        vehicle.setRateCurrency(request.rateCurrency());
        vehicle.setOwnership(request.ownership());
        vehicle.setVendor("Vendor".equals(request.ownership()) ? trim(request.vendor()) : null);
        vehicle.setStatus(request.status());
        vehicle.setCustodian(trim(request.custodian()));
        vehicle.setOnSite(request.onSite());
        vehicle.setListedForBooking(request.listedForBooking());
        vehicle.setShowOnPublicSite(request.showOnPublicSite());
        vehicle.setServiceIntervalKm(request.serviceIntervalKm());
        vehicle.setLastServiceKm(request.lastServiceKm());
        vehicle.setCurrentOdometerKm(request.currentOdometerKm());
        vehicle.setOilIntervalKm(request.oilIntervalKm());
        vehicle.setLastOilKm(request.lastOilKm());
        vehicle.setTyreIntervalKm(request.tyreIntervalKm());
        vehicle.setLastTyreKm(request.lastTyreKm());
        vehicle.setBrakeIntervalKm(request.brakeIntervalKm());
        vehicle.setLastBrakeKm(request.lastBrakeKm());
    }

    private VehicleResponse response(Vehicle vehicle, List<VehicleFile> records) {
        VehicleRequest details = new VehicleRequest(vehicle.getMake(), vehicle.getModel(), vehicle.getRegistration(),
                vehicle.getYear(), vehicle.getColor(), vehicle.getVehicleId(), vehicle.getDoorNo(), vehicle.getChassisNo(),
                vehicle.getType(), vehicle.getSeats(), vehicle.getTransmission(), vehicle.getFuelType(),
                List.copyOf(vehicle.getFeatureTags()), vehicle.getHourlyRate(), vehicle.getDailyRate(), vehicle.getMonthlyRate(),
                vehicle.getRateCurrency(), vehicle.getOwnership(), vehicle.getVendor(), vehicle.getStatus(), vehicle.getCustodian(),
                vehicle.isOnSite(), vehicle.isListedForBooking(), vehicle.isShowOnPublicSite(), vehicle.getServiceIntervalKm(),
                vehicle.getLastServiceKm(), vehicle.getCurrentOdometerKm(), vehicle.getOilIntervalKm(), vehicle.getLastOilKm(),
                vehicle.getTyreIntervalKm(), vehicle.getLastTyreKm(), vehicle.getBrakeIntervalKm(), vehicle.getLastBrakeKm());
        List<VehicleFileResponse> images = records.stream().filter(file -> "IMAGE".equals(file.getKind())).map(VehicleFileMapper::toResponse).toList();
        List<VehicleFileResponse> documents = records.stream().filter(file -> "DOCUMENT".equals(file.getKind())).map(VehicleFileMapper::toResponse).toList();
        String documentStatus = documents.stream().anyMatch(file -> file.expiryDate() != null && file.expiryDate().isBefore(LocalDate.now())) ? "Expired"
                : documents.stream().map(VehicleFileResponse::type).collect(Collectors.toSet()).containsAll(REQUIRED_DOCUMENTS) ? "Complete" : "Missing";
        String owner = "Vendor".equals(vehicle.getOwnership()) ? vehicle.getVendor() : "Leased".equals(vehicle.getOwnership()) ? "Leased Fleet" : "Company Fleet";
        return new VehicleResponse(vehicle.getId(), details, owner, documentStatus, images, documents);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
