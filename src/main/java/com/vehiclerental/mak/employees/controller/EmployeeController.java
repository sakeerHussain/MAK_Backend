package com.vehiclerental.mak.employees.controller;

import com.vehiclerental.mak.common.dto.response.FileDownload;
import com.vehiclerental.mak.employees.dto.request.EmployeeRequest;
import com.vehiclerental.mak.employees.dto.request.EmployeeStatusRequest;
import com.vehiclerental.mak.employees.dto.response.EmployeeFileResponse;
import com.vehiclerental.mak.employees.dto.response.EmployeeImportResult;
import com.vehiclerental.mak.employees.dto.response.EmployeeResponse;
import com.vehiclerental.mak.employees.service.EmployeeFileService;
import com.vehiclerental.mak.employees.service.EmployeeImportService;
import com.vehiclerental.mak.employees.service.EmployeeSaveService;
import com.vehiclerental.mak.employees.service.EmployeeService;

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
@RequestMapping("/api/employees")
public class EmployeeController {
    private final EmployeeService employees;
    private final EmployeeFileService files;
    private final EmployeeImportService imports;
    private final EmployeeSaveService saves;

    public EmployeeController(EmployeeService employees, EmployeeFileService files, EmployeeImportService imports, EmployeeSaveService saves) {
        this.employees = employees;
        this.files = files;
        this.imports = imports; this.saves = saves;
    }

    @PostMapping(value="/save",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeResponse saveNew(@Valid @RequestPart("record") EmployeeRequest record,
            @RequestPart("attachments") java.util.Map<String,Object> attachments,
            @RequestParam org.springframework.util.MultiValueMap<String,MultipartFile> uploads) { return saves.save(null,record,attachments,uploads); }
    @PutMapping(value="/{id}/save",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeResponse save(@PathVariable long id,@Valid @RequestPart("record") EmployeeRequest record,
            @RequestPart("attachments") java.util.Map<String,Object> attachments,
            @RequestParam org.springframework.util.MultiValueMap<String,MultipartFile> uploads) { return saves.save(id,record,attachments,uploads); }

    @GetMapping
    public Page<EmployeeResponse> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String department, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return employees.list(search, department, status, page, size);
    }

    @PostMapping("/import")
    public EmployeeImportResult importRows(@RequestBody List<EmployeeRequest> rows) { return imports.importRows(rows); }

    @GetMapping("/{id}")
    public EmployeeResponse get(@PathVariable long id) { return employees.get(id); }

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody EmployeeRequest request) {
        EmployeeResponse created = employees.create(request);
        return ResponseEntity.created(URI.create("/api/employees/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public EmployeeResponse update(@PathVariable long id, @Valid @RequestBody EmployeeRequest request) { return employees.update(id, request); }

    @PatchMapping("/{id}/status")
    public EmployeeResponse status(@PathVariable long id, @Valid @RequestBody EmployeeStatusRequest request) {
        return employees.updateStatus(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable long id) { employees.archive(id); return ResponseEntity.noContent().build(); }

    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeFileResponse photo(@PathVariable long id, @RequestPart("file") MultipartFile file) { return files.uploadPhoto(id, file); }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeFileResponse document(@PathVariable long id, @RequestParam String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @RequestPart("file") MultipartFile file) { return files.uploadDocument(id, type, expiryDate, file); }

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
