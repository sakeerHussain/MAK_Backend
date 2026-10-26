package com.vehiclerental.mak.fleet.controller;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.dto.request.*;
import com.vehiclerental.mak.fleet.dto.response.*;
import com.vehiclerental.mak.fleet.service.VehicleImportDraftService;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/vehicles/import-drafts")
public class VehicleImportDraftController {
    private final VehicleImportDraftService drafts;
    public VehicleImportDraftController(VehicleImportDraftService drafts) { this.drafts = drafts; }
    @GetMapping public List<VehicleImportDraftResponse> list() { return drafts.list(); }
    @PostMapping public VehicleImportResult saveRows(@RequestBody List<VehicleImportDraftRequest> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "Import between 1 and 2,000 draft rows.");
        var results = new ArrayList<VehicleImportRow>();
        for (int i = 0; i < rows.size(); i++) {
            var request = rows.get(i);
            var details = request == null ? null : request.details();
            String registration = details == null ? "" : details.registration(), vehicleId = details == null ? "" : details.vehicleId();
            try {
                var saved = drafts.save(null, request);
                results.add(new VehicleImportRow(i + 2, registration, vehicleId, saved.id(), null));
            } catch (ApiException error) {
                results.add(new VehicleImportRow(i + 2, registration, vehicleId, null, error.getMessage()));
            } catch (DataIntegrityViolationException error) {
                results.add(new VehicleImportRow(i + 2, registration, vehicleId, null, "Duplicate registration number or vehicle ID in import drafts."));
            }
        }
        long successful = results.stream().filter(row -> row.error() == null).count();
        StringBuilder errors = new StringBuilder("Row,Registration Number,Vehicle ID,Errors\r\n");
        results.stream().filter(row -> row.error() != null).forEach(row -> errors.append(row.rowNumber()).append(',').append(csv(row.registration())).append(',').append(csv(row.vehicleId())).append(',').append(csv(row.error())).append("\r\n"));
        return new VehicleImportResult(successful, results.size() - successful, results, errors.toString());
    }
    @PutMapping("/{id}") public VehicleImportDraftResponse update(@PathVariable long id, @Valid @RequestBody VehicleImportDraftRequest request) { return drafts.save(id, request); }
    @PostMapping("/{id}/complete") public VehicleResponse complete(@PathVariable long id, @Valid @RequestBody VehicleDraftCompletionRequest request) { return drafts.complete(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void discard(@PathVariable long id, @RequestParam long version) { drafts.discard(id, version); }
    private String csv(String value) {
        String text = value == null ? "" : value;
        if (text.matches("^[\\s]*[=+@-].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
