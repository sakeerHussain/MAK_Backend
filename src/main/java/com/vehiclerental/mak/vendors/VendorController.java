package com.vehiclerental.mak.vendors;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
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

@RestController @RequestMapping("/api/vendors")
public class VendorController {
    private final VendorService vendors;
    private final VendorFileService files;

    public VendorController(VendorService vendors, VendorFileService files) { this.vendors = vendors; this.files = files; }

    @GetMapping
    public Page<VendorResponse> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return vendors.list(search, type, status, page, size);
    }

    @GetMapping("/{id}")
    public VendorResponse get(@PathVariable long id) { return vendors.get(id); }

    @PostMapping
    public ResponseEntity<VendorResponse> create(@Valid @RequestBody VendorRequest request) {
        VendorResponse created = vendors.create(request);
        return ResponseEntity.created(URI.create("/api/vendors/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public VendorResponse update(@PathVariable long id, @Valid @RequestBody VendorRequest request) {
        return vendors.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public VendorResponse status(@PathVariable long id, @Valid @RequestBody VendorStatusRequest request) {
        return vendors.updateStatus(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable long id) { vendors.archive(id); return ResponseEntity.noContent().build(); }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VendorResponse.Attachment upload(@PathVariable long id, @RequestParam String type,
            @RequestPart("file") MultipartFile file) { return files.upload(id, type, file); }

    @GetMapping("/{id}/files/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable long id, @PathVariable long fileId) {
        VendorFileService.FileDownload file = files.download(id, fileId);
        ContentDisposition disposition = (file.contentType().startsWith("image/") ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType())).body(file.resource());
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public ResponseEntity<Void> deleteFile(@PathVariable long id, @PathVariable long fileId) {
        files.delete(id, fileId); return ResponseEntity.noContent().build();
    }
}
