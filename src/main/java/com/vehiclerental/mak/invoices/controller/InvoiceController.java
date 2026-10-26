package com.vehiclerental.mak.invoices.controller;

import com.vehiclerental.mak.auth.security.AccountPrincipal;
import org.springframework.security.core.Authentication;
import com.vehiclerental.mak.invoices.service.InvoicePdfService;
import com.vehiclerental.mak.invoices.service.InvoiceService;
import com.vehiclerental.mak.invoices.service.InvoicePaymentKind;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/invoices")
public class InvoiceController {
    private final com.vehiclerental.mak.auth.service.AccountService accounts;
    private final InvoiceService service;
    private final InvoicePdfService pdf;
    public InvoiceController(InvoiceService service, InvoicePdfService pdf,com.vehiclerental.mak.auth.service.AccountService accounts) { this.accounts=accounts;this.service = service; this.pdf = pdf; }
    @GetMapping public List<Map<String, Object>> list() { return service.list(); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable long id) { return service.get(id); }
    @GetMapping(value="/{id}/pdf", produces="application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable long id) {
        Map<String,Object> invoice=service.get(id);
        String filename=("Draft".equals(invoice.get("status")) ? "draft-"+id : String.valueOf(invoice.get("invoiceNumber")))+".pdf";
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename(filename).build().toString())
            .header("Cache-Control","no-store").body(pdf.render(invoice));
    }
    @PostMapping("/{id}/email") public Map<String,Object> email(@PathVariable long id) { return service.email(id); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> data, @RequestParam(defaultValue = "false") boolean issue) { if(issue) accounts.requirePermission("invoices.issue"); return service.create(data, issue); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> data, @RequestParam(defaultValue = "false") boolean issue) { if(issue) accounts.requirePermission("invoices.issue"); return service.update(id, data, issue); }
    @PostMapping("/{id}/issue") public Map<String, Object> issue(@PathVariable long id) { return service.issue(id); }
    @PostMapping("/{id}/credit-notes") public Map<String,Object> credit(@PathVariable long id,@RequestBody Map<String,Object> data,Authentication authentication) { return service.credit(id,data,((AccountPrincipal)authentication.getPrincipal()).id()); }
    @GetMapping(value="/{id}/credit-notes/{noteId}/pdf",produces="application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> creditPdf(@PathVariable long id,@PathVariable long noteId) {
        Map<String,Object> note=service.creditNote(id,noteId);
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=\""+note.get("number")+".pdf\"")
            .header("Cache-Control","no-store").body(pdf.renderCreditNote(note));
    }
    @PostMapping("/{id}/discard") public Map<String,Object> discard(@PathVariable long id, Authentication authentication) { return service.discard(id, ((AccountPrincipal)authentication.getPrincipal()).id()); }
    @PostMapping("/{id}/payments") public Map<String, Object> payment(@PathVariable long id, @RequestBody Map<String, Object> data) { accounts.requirePermission(InvoicePaymentKind.parse(data.get("kind")).permission());return service.payment(id, data); }
}
