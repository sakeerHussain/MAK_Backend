package com.vehiclerental.mak.customers;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import java.nio.charset.StandardCharsets;

@RestController @RequestMapping("/api")
public class QuoteController {
    private final QuoteService quotes;
    public QuoteController(QuoteService quotes) { this.quotes = quotes; }
    @GetMapping("/quotes") public Page<QuoteResponse> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return quotes.list(page, size); }
    @GetMapping("/quotes/{id}") public QuoteResponse get(@PathVariable String id) { return quotes.get(id); }
    @PostMapping("/customers/{customerId}/quotes") public QuoteResponse create(@PathVariable long customerId, @Valid @RequestBody QuoteRequest request) { return quotes.create(customerId, request); }
    @PutMapping("/quotes/{id}") public QuoteResponse update(@PathVariable String id, @Valid @RequestBody QuoteRequest request) { return quotes.update(id, request); }
    @PostMapping("/quotes/{id}/approve") public QuoteResponse approve(@PathVariable String id, @Valid @RequestBody QuoteApprovalRequest request) { return quotes.approve(id, request); }
    @PostMapping("/quotes/{id}/reject") public QuoteResponse reject(@PathVariable String id) { return quotes.transition(id, "Rejected"); }
    @PostMapping("/quotes/{id}/supersede") public QuoteResponse supersede(@PathVariable String id) { return quotes.transition(id, "Superseded"); }
    @PostMapping(value = "/quotes/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuoteResponse upload(@PathVariable String id, @RequestPart("file") MultipartFile file) { return quotes.uploadEvidence(id, file); }
    @GetMapping("/quotes/{id}/evidence")
    public ResponseEntity<byte[]> evidence(@PathVariable String id) {
        QuoteEvidence file = quotes.evidence(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").contentType(MediaType.parseMediaType(file.getContentType())).body(file.getFileData());
    }
}
