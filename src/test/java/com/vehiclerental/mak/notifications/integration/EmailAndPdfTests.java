package com.vehiclerental.mak.notifications.integration;

import com.vehiclerental.mak.invoices.service.InvoicePdfService;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;
import com.vehiclerental.mak.notifications.email.service.EmailDelivery;
import com.vehiclerental.mak.notifications.email.service.EmailService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.regex.Pattern;
import java.awt.geom.Rectangle2D;
import java.math.BigDecimal;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

@SpringBootTest
class EmailAndPdfTests {
    @Autowired EmailService emails;
    @Autowired EmailRepository repository;
    @Autowired EmailDelivery delivery;
    @Autowired InvoicePdfService pdf;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean JavaMailSender sender;

    private Map<String,Object> invoice(long id,int count) {
        var lines=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++) lines.add(Map.of("description","Airport transfer and planned waiting for passenger group "+(i+1),"unit","Trip","unitPrice",100,"quantity",1,"discountPercent",10,"taxPercent",15,"sourceRef","CT-3010"));
        return Map.ofEntries(Map.entry("id",id),Map.entry("invoiceNumber","INV-"+id),Map.entry("status","Issued"),
            Map.entry("kind","Corporate"),Map.entry("issuedAt","2026-10-05T10:00:00Z"),Map.entry("dueDate","2026-10-30"),
            Map.entry("paymentTerms","30 days"),Map.entry("transactionType","Corporate Trip"),Map.entry("poNumber","PO-2026"),
            Map.entry("issuer",Map.of("name","MAK Car Rental","street","King Fahd Road","buildingNumber","1234","city","Al Khobar","country","Saudi Arabia","postalCode","31952","taxNumber","300000000000003")),
            Map.entry("billTo",Map.of("name","Example Engineering Company","street","Prince Turki Street","city","Dammam","country","Saudi Arabia","email","billing@example.com","taxNumber","310000000000003")),
            Map.entry("lines",lines),Map.entry("payments",List.of(Map.of("kind","Payment","amount",30))),
            Map.entry("sources",List.of(Map.of("reference","CT-3010","serviceDate","2026-10-05"))),
            Map.entry("notes","Thank you for your business. Please include the invoice number with your payment."),
            Map.entry("paymentContact","Accounts Department"),Map.entry("account",Map.of("accountName","MAK Car Rental","bankName","Example Bank","branchName","Al Khobar","accountNumber","1234567890","iban","SA0000000000000000000000")));
    }
    @Test void pdfPaginatesAndPreservesAmountsAndAllSections() throws Exception {
        byte[] bytes=pdf.render(invoice(9001,45));
        assertEquals("%PDF",new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII));
        try(var doc=Loader.loadPDF(bytes)) {
            assertTrue(doc.getNumberOfPages()>2);
            String text=new PDFTextStripper().getText(doc).replaceAll("\\s+"," ");
            for(String label:List.of("Tax Invoice","Seller","Buyer","Invoice items","Description",
                    "Invoice totals","Payment history","Tax summary","Bank details","Receiving account",
                    "Notes","Booking references"))
                assertTrue(text.contains(label),"Missing current invoice section: "+label);
            for(String detail:List.of("INV-9001","PO-2026","2026-10-30","30 days",
                    "MAK Car Rental","King Fahd Road","300000000000003",
                    "Example Engineering Company","Prince Turki Street","310000000000003",
                    "Example Bank","1234567890",
                    "Accounts Department","Thank you for your business.","CT-3010"))
                assertTrue(text.contains(detail),"Missing invoice detail: "+detail);
            // The bank column wraps the long IBAN; preserve every character while allowing line breaks.
            assertTrue(text.replace(" ","").contains("SA0000000000000000000000"),"Missing complete IBAN.");
            // Independent fixture expectations: 45 x (100 - 10%) = 4,050;
            // VAT 15% = 607.50; total = 4,657.50; payment 30 leaves 4,627.50.
            for(String[] amount:List.of(new String[]{"Subtotal","4,050.00"},new String[]{"VAT","607.50"},
                    new String[]{"Invoice total","4,657.50"},new String[]{"Net received","30.00"},
                    new String[]{"Balance due","4,627.50"}))
                assertTrue(Pattern.compile("\\b"+Pattern.quote(amount[0])+"\\b[^0-9]*"
                        +Pattern.quote(amount[1])+"\\b").matcher(text).find(),
                        "Incorrect or missing "+amount[0]+": expected "+amount[1]);
            assertTrue(text.contains("PARTIALLY PAID"),"The opening payment must leave a balance.");
            var pages=new PDFTextStripper();
            var descriptions=new StringBuilder();
            for(int page=1;page<=doc.getNumberOfPages();page++) {
                pages.setStartPage(page);pages.setEndPage(page);
                String pageText=pages.getText(doc).replaceAll("\\s+"," ");
                assertTrue(pageText.contains("INV-9001"),"Missing invoice reference on page "+page);
                assertTrue(pageText.contains("Page "+page),"Missing page number on page "+page);
                // Follow wrapped descriptions across pages without intervening headers or amount columns.
                var columns=new PDFTextStripperByArea();
                columns.addRegion("description",new Rectangle2D.Float(52,172,185,620));
                columns.extractRegions(doc.getPage(page-1));
                descriptions.append(columns.getTextForRegion("description")).append(' ');
            }
            String descriptionText=descriptions.toString().replaceAll("\\s+"," ");
            for(int group=1;group<=45;group++)
                assertTrue(Pattern.compile("\\bgroup "+group+"\\b").matcher(descriptionText).find(),
                        "Missing charge description for passenger group "+group);
        }
        Path sample=Path.of("target/pdf-qa/invoice-sample.pdf");Files.createDirectories(sample.getParent());Files.write(sample,pdf.render(invoice(9001,4)));
        Files.write(sample.resolveSibling("invoice-long.pdf"),bytes);
    }
    @Test void preferenceOutboxDeduplicationAndAttachmentSnapshot() throws Exception {
        long id=System.nanoTime();
        var rental=new LinkedHashMap<String,Object>(Map.of("id",id,"customerName","Guest","customerEmail","guest@example.com","bookingId","BK-9010","status","Confirmed","totalAmount",new BigDecimal("200")));
        rental.put("emailNotifications",false);emails.rental("Individual",rental,"Booking created");
        assertTrue(repository.findByRecordKindAndRecordIdOrderByIdDesc("Individual",id).isEmpty());
        rental.put("emailNotifications",true);emails.rental("Individual",rental,"Booking created");
        var message=repository.findByRecordKindAndRecordIdOrderByIdDesc("Individual",id).getFirst();assertEquals("Queued",message.getStatus());assertTrue(message.getBody().contains("\nStatus:"));
        var sent=emails.invoice(invoice(id,1));var duplicate=emails.invoice(invoice(id,1));assertEquals(sent.get("id"),duplicate.get("id"));
        var attachment=repository.findById(((Number)sent.get("id")).longValue()).orElseThrow();
        assertEquals("INV-"+id+".pdf",attachment.getAttachmentName());
        try(var doc=Loader.loadPDF(Base64.getDecoder().decode(attachment.getAttachment()))) { assertTrue(new PDFTextStripper().getText(doc).contains("103.50")); }
    }
    @Test void smtpFailuresAreVisibleAndRetryIsSafe() throws Exception {
        long id=System.nanoTime();var queued=emails.invoice(invoice(id,1));long emailId=((Number)queued.get("id")).longValue();
        when(sender.createMimeMessage()).thenAnswer(invocation->new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("secret provider response")).when(sender).send(any(MimeMessage.class));
        delivery.deliver(emailId);
        var failed=repository.findById(emailId).orElseThrow();assertEquals("Failed",failed.getStatus());assertFalse(failed.getLastError().contains("secret"));
        emails.retry(emailId);assertEquals("Queued",repository.findById(emailId).orElseThrow().getStatus());
        doNothing().when(sender).send(any(MimeMessage.class));
        delivery.deliver(emailId);
        assertEquals("Sent",repository.findById(emailId).orElseThrow().getStatus());
        assertNotNull(repository.findById(emailId).orElseThrow().getSentAt());
        delivery.deliver(emailId);
        verify(sender,times(2)).send(any(MimeMessage.class));
        var capture=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender,times(2)).send(capture.capture());
        var mime=capture.getValue();mime.saveChanges();
        assertEquals("billing@example.com",mime.getAllRecipients()[0].toString());
        String wire=new String(mime.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(wire.contains("application/pdf"));
        assertTrue(wire.contains("INV-"+id+".pdf"));
    }
    @Test void rolledBackOperationNeverLeavesEmailToSend() {
        long id=System.nanoTime();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class,()->tx.execute(status->{emails.invoice(invoice(id,1));throw new IllegalStateException("rollback");}));
        assertTrue(repository.findByRecordKindAndRecordIdOrderByIdDesc("Invoice",id).isEmpty());
    }
}
