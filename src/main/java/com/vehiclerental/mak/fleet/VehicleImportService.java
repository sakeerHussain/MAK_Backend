package com.vehiclerental.mak.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.vehiclerental.mak.common.ApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class VehicleImportService {
    private static final String HEADER = "Make,Model,Registration Number,Year of Registration,Color,Vehicle ID,Door No.,Chassis No.,Type,Seats,Transmission,Fuel Type,Feature Tags,Hourly Rate (USD),Daily Rate (USD),Monthly Rate (USD),Ownership,Vendor,Status,Custodian,On-site?,Listed for Booking,Show on Public Site,Service Interval (km),Last Service (km),Current Odometer (km),Oil Interval (km),Last Oil (km),Tyre Interval (km),Last Tyre (km),Brake Interval (km),Last Brake (km)";
    private static final String EXAMPLE = "Toyota,Camry,RNT-9001,2024,Silver,VH-9001,D-12,JT123456789012345,Sedan,5,Automatic,Hybrid,\"GPS, Bluetooth\",12.00,72.00,1800.00,Owned,,Available,Unassigned,Yes,Yes,No,10000,25000,30000,10000,25000,40000,10000,30000,10000";
    private final VehicleService vehicles;
    private final Validator validator;

    public VehicleImportService(VehicleService vehicles, Validator validator) {
        this.vehicles = vehicles;
        this.validator = validator;
    }

    public String template() {
        return HEADER + "\r\n" + EXAMPLE + "\r\n";
    }

    public ImportResult importRows(List<VehicleRequest> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "Import between 1 and 2,000 vehicles.");
        List<ImportRow> results = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            VehicleRequest request = rows.get(index);
            String registration = request == null ? "" : request.registration();
            String vehicleId = request == null ? "" : request.vehicleId();
            if (request == null) {
                results.add(new ImportRow(index + 2, registration, vehicleId, null, "Vehicle row is empty."));
                continue;
            }
            String violations = validator.validate(request).stream().map(this::message).sorted().collect(Collectors.joining("; "));
            if (!violations.isEmpty()) {
                results.add(new ImportRow(index + 2, registration, vehicleId, null, violations));
                continue;
            }
            try {
                VehicleResponse created = vehicles.create(request);
                results.add(new ImportRow(index + 2, registration, vehicleId, created.id(), null));
            } catch (ApiException error) {
                results.add(new ImportRow(index + 2, registration, vehicleId, null, error.getMessage()));
            } catch (DataIntegrityViolationException error) {
                results.add(new ImportRow(index + 2, registration, vehicleId, null, "Duplicate registration number or vehicle ID."));
            }
        }
        long successful = results.stream().filter(row -> row.error() == null).count();
        StringBuilder errors = new StringBuilder("Row,Registration Number,Vehicle ID,Errors\r\n");
        results.stream().filter(row -> row.error() != null).forEach(row -> errors.append(row.rowNumber()).append(',')
                .append(csv(row.registration())).append(',').append(csv(row.vehicleId())).append(',')
                .append(csv(row.error())).append("\r\n"));
        return new ImportResult(successful, results.size() - successful, results, errors.toString());
    }

    private String message(ConstraintViolation<VehicleRequest> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("^[\\s]*[=+@-].*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public record ImportRow(int rowNumber, String registration, String vehicleId, Long id, String error) { }
    public record ImportResult(long successful, long failed, List<ImportRow> rows, String errorReportCsv) { }
}
