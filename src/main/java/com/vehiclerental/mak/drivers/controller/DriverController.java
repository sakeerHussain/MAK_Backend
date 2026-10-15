package com.vehiclerental.mak.drivers.controller;

import com.vehiclerental.mak.common.dto.response.FileDownload;
import com.vehiclerental.mak.drivers.dto.request.CertificationRequest;
import com.vehiclerental.mak.drivers.dto.request.DriverRequest;
import com.vehiclerental.mak.drivers.dto.request.DriverStatusRequest;
import com.vehiclerental.mak.drivers.dto.response.DriverFileResponse;
import com.vehiclerental.mak.drivers.dto.response.DriverImportResult;
import com.vehiclerental.mak.drivers.dto.response.DriverResponse;
import com.vehiclerental.mak.drivers.service.DriverFileService;
import com.vehiclerental.mak.drivers.service.DriverImportService;
import com.vehiclerental.mak.drivers.service.DriverService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
@RequestMapping("/api/drivers")
public class DriverController {
    private final DriverService drivers;
    private final DriverFileService files;
    private final DriverImportService imports;

    public DriverController(DriverService drivers, DriverFileService files, DriverImportService imports) {
        this.drivers = drivers;
        this.files = files;
        this.imports = imports;
    }

    @GetMapping
    public Page<DriverResponse> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String category, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return drivers.list(search, category, status, page, size);
    }

    @PostMapping("/import")
    public DriverImportResult importRows(@RequestBody List<DriverRequest> rows) { return imports.importRows(rows); }

    @GetMapping("/{id}")
    public DriverResponse get(@PathVariable long id) { return drivers.get(id); }

    @PostMapping
    public ResponseEntity<DriverResponse> create(@Valid @RequestBody DriverRequest request) {
        DriverResponse created = drivers.create(request);
        return ResponseEntity.created(URI.create("/api/drivers/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public DriverResponse update(@PathVariable long id, @Valid @RequestBody DriverRequest request) { return drivers.update(id, request); }

    @PatchMapping("/{id}/status")
    public DriverResponse status(@PathVariable long id, @Valid @RequestBody DriverStatusRequest request) {
        return drivers.updateStatus(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable long id) { drivers.archive(id); return ResponseEntity.noContent().build(); }

    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DriverFileResponse photo(@PathVariable long id, @RequestPart("file") MultipartFile file) { return files.uploadPhoto(id, file); }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DriverFileResponse document(@PathVariable long id, @RequestParam String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @RequestPart("file") MultipartFile file) { return files.uploadDocument(id, type, expiryDate, file); }

    @PostMapping(value = "/{id}/certifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DriverFileResponse certification(@PathVariable long id, @RequestParam String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return files.addCertification(id, new CertificationRequest(name, issueDate, expiryDate), file);
    }

    @PatchMapping("/{id}/certifications/{fileId}")
    public DriverFileResponse updateCertification(@PathVariable long id, @PathVariable long fileId,
            @Valid @RequestBody CertificationRequest request) { return files.updateCertification(id, fileId, request); }

    @GetMapping("/{id}/files/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable long id, @PathVariable long fileId) {
        FileDownload file = files.download(id, fileId);
        ContentDisposition disposition = (file.contentType().startsWith("image/") ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType())).body(file.resource());
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public ResponseEntity<Void> deleteFile(@PathVariable long id, @PathVariable long fileId) {
        files.delete(id, fileId);
        return ResponseEntity.noContent().build();
    }
}
