package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.auth.security.AccountPrincipal;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.vehiclerental.mak.payroll.PayrollData.*;

@RestController
@RequestMapping("/api/payroll")
public class PayrollController {
    private final PayrollService payroll;
    private final PayrollSettlementService settlement;
    private final PayrollPayslipService payslips;
    private final PayrollPayslipPdf pdf;
    public PayrollController(PayrollService payroll,PayrollSettlementService settlement,PayrollPayslipService payslips,PayrollPayslipPdf pdf) { this.payroll=payroll; this.settlement=settlement; this.payslips=payslips; this.pdf=pdf; }
    @GetMapping public Map<String,Object> list(@RequestParam String period) { return payroll.list(period); }
    @GetMapping("/drafts/{id}") public Map<String,Object> get(@PathVariable long id) { return payroll.get(id); }
    @PostMapping("/profiles") public Map<String,Object> createProfile(@RequestBody ProfileRequest data,Authentication auth) { return payroll.saveProfile(null,data,actor(auth)); }
    @PutMapping("/profiles/{id}") public Map<String,Object> updateProfile(@PathVariable long id,@RequestBody ProfileRequest data,Authentication auth) { return payroll.saveProfile(id,data,actor(auth)); }
    @PostMapping("/drafts") public List<Map<String,Object>> prepare(@RequestBody DraftRequest data,Authentication auth) { return payroll.prepare(data,actor(auth)); }
    @PutMapping("/drafts/{id}") public Map<String,Object> update(@PathVariable long id,@RequestBody DraftUpdate data,Authentication auth) { return payroll.update(id,data,actor(auth)); }
    @DeleteMapping("/drafts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable long id,@RequestParam long version,Authentication auth) { payroll.discard(id,version,actor(auth)); }
    @PostMapping("/drafts/{id}/approve") public Map<String,Object> approve(@PathVariable long id,@RequestBody ApprovalRequest data,Authentication auth) { settlement.approve(id,data,actor(auth));return payroll.get(id); }
    @PostMapping("/drafts/{id}/reopen") public Map<String,Object> reopen(@PathVariable long id,@RequestBody CorrectionRequest data,Authentication auth) { settlement.reopen(id,data,actor(auth));return payroll.get(id); }
    @PostMapping(value="/drafts/{id}/payments",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> payment(@PathVariable long id,@RequestBody PaymentRequest data,Authentication auth) { settlement.record(id,data,null,actor(auth));return payroll.get(id); }
    @PostMapping(value="/drafts/{id}/payments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> paymentWithReceipt(@PathVariable long id,@RequestPart("record") PaymentRequest data,@RequestPart(value="receipt",required=false) MultipartFile receipt,Authentication auth) { settlement.record(id,data,receipt,actor(auth));return payroll.get(id); }
    @PostMapping("/drafts/{id}/payments/{paymentId}/reverse")
    public Map<String,Object> reverse(@PathVariable long id,@PathVariable long paymentId,@RequestBody CorrectionRequest data,Authentication auth) { settlement.reverse(id,paymentId,data,actor(auth));return payroll.get(id); }
    @PostMapping(value="/drafts/{id}/payments/{paymentId}/receipt",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> receipt(@PathVariable long id,@PathVariable long paymentId,@RequestParam long version,@RequestPart("receipt") MultipartFile receipt,Authentication auth) { settlement.attachReceipt(id,paymentId,version,receipt,actor(auth));return payroll.get(id); }
    @GetMapping("/drafts/{id}/payments/{paymentId}/receipt")
    public ResponseEntity<Resource> receipt(@PathVariable long id,@PathVariable long paymentId,@RequestParam(defaultValue="false") boolean download) {
        var file=settlement.receipt(id,paymentId);
        var disposition=(download?ContentDisposition.attachment():ContentDisposition.inline()).filename(file.name(),java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(file.contentType())).body(file.resource());
    }
    @GetMapping(value="/drafts/{id}/payslip",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> payslip(@PathVariable long id) {
        var data=payslips.snapshot(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(data.filename()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(pdf.render(data));
    }
    private long actor(Authentication auth) { return ((AccountPrincipal)auth.getPrincipal()).id(); }
}
