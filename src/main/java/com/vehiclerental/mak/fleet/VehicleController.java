package com.vehiclerental.mak.fleet;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {
    private final VehicleService vehicles;
    private final VehicleFileService files;
    private final VehicleImportService imports;

    public VehicleController(VehicleService vehicles, VehicleFileService files, VehicleImportService imports) {
        this.vehicles = vehicles;
        this.files = files;
        this.imports = imports;
    }

    @GetMapping
    public Page<VehicleResponse> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String status, @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean onSite, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return vehicles.list(search, status, type, onSite, page, size);
    }

    @GetMapping("/summary")
    public VehicleSummary summary() {
        return vehicles.summary();
    }

    @GetMapping(value = "/import/template", produces = "text/csv")
    public ResponseEntity<String> importTemplate() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fleet-template.csv")
                .body(imports.template());
    }

    @PostMapping("/import")
    public VehicleImportService.ImportResult importRows(@RequestBody List<VehicleRequest> rows) {
        return imports.importRows(rows);
    }

    @GetMapping("/{id}")
    public VehicleResponse get(@PathVariable long id) {
        return vehicles.get(id);
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> create(@Valid @RequestBody VehicleRequest request) {
        VehicleResponse created = vehicles.create(request);
        return ResponseEntity.created(URI.create("/api/vehicles/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public VehicleResponse update(@PathVariable long id, @Valid @RequestBody VehicleRequest request) {
        return vehicles.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public VehicleResponse updateStatus(@PathVariable long id, @Valid @RequestBody VehicleStatusRequest request) {
        return vehicles.updateStatus(id, request);
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VehicleFileResponse> uploadImage(@PathVariable long id, @RequestPart("file") MultipartFile file) {
        VehicleFileResponse created = files.uploadImage(id, file);
        return ResponseEntity.created(URI.create(created.url())).body(created);
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VehicleFileResponse> uploadDocument(@PathVariable long id, @RequestParam String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @RequestPart("file") MultipartFile file) {
        VehicleFileResponse created = files.uploadDocument(id, type, expiryDate, file);
        return ResponseEntity.created(URI.create(created.url())).body(created);
    }

    @GetMapping("/{id}/files/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable long id, @PathVariable long fileId) {
        VehicleFileService.FileDownload file = files.download(id, fileId);
        ContentDisposition disposition = (file.contentType().startsWith("image/") ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.name(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType())).body(file.resource());
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public ResponseEntity<Void> deleteFile(@PathVariable long id, @PathVariable long fileId) {
        files.delete(id, fileId);
        return ResponseEntity.noContent().build();
    }
}
