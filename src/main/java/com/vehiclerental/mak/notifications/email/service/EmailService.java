package com.vehiclerental.mak.notifications.email.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.drivers.repository.DriverRepository;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.invoices.service.InvoicePdfService;
import com.vehiclerental.mak.notifications.email.entity.EmailMessage;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailService {
    private final EmailRepository emails;
    private final InvoicePdfService pdf;
    private final boolean enabled;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    public EmailService(EmailRepository emails,InvoicePdfService pdf,@Value("${app.mail.enabled:false}") boolean enabled,
            VehicleRepository vehicles,DriverRepository drivers) {
        this.emails=emails; this.pdf=pdf; this.enabled=enabled; this.vehicles=vehicles; this.drivers=drivers;
    }
    // Enqueued inside the caller's transaction: rolled-back rentals never send email.
    public void rental(String kind,Map<String,Object> record,String event) {
        if (!Boolean.TRUE.equals(record.get("emailNotifications"))) return;
        String recipient=text(record.get(kind.equals("Corporate")?"contactEmail":"customerEmail"));
        String reference=text(record.get(kind.equals("Corporate")?"tripId":"bookingId"));
        if (reference.isBlank()) reference="Booking #"+record.get("id");
        String name=text(record.get(kind.equals("Corporate")?"contactName":"customerName"));
        String vehicle="To be assigned", driver="To be assigned";
        if(record.get("vehicleId") instanceof Number vehicleId) vehicle=vehicles.findById(vehicleId.longValue())
            .map(v->v.getMake()+" "+v.getModel()+" ("+v.getRegistration()+")").orElse("To be assigned");
        if(record.get("driverId") instanceof Number driverId) driver=drivers.findById(driverId.longValue())
            .map(d->d.getName()+" - "+d.getPhone()).orElse("To be assigned");
        if("Self-drive".equals(record.get("driverMode")) || "Self-drive".equals(record.get("serviceMode"))) driver="Self-drive";
        String body="Hello "+name+",\n\n"+event+" for "+reference+".\nStatus: "+record.get("status")
            +"\nPickup: "+record.get("pickupAt")+"\nReturn: "+record.get("returnAt")
            +"\nPickup location: "+text(record.get("pickupLocation"))+"\nDestination: "+text(record.get("destination"))
            +"\nTotal: SAR "+record.get("totalAmount")
            +"\nVehicle: "+vehicle+"\nDriver: "+driver
            +"\n\nFor advance bookings, the vehicle and driver are assigned on the trip day.\nPlease contact us if you need assistance.";
        enqueue(kind,((Number)record.get("id")).longValue(),recipient,reference+" - "+event,body,null,null);
    }
    public Map<String,Object> invoice(Map<String,Object> invoice) {
        if (!"Issued".equals(invoice.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"Issue the invoice before emailing it.");
        Map<?,?> buyer=(Map<?,?>)invoice.get("billTo");
        String number=text(invoice.get("invoiceNumber"));
        long id=((Number)invoice.get("id")).longValue();
        // Repeated clicks reuse the outstanding message rather than queueing duplicates.
        for (EmailMessage message:emails.findByRecordKindAndRecordIdOrderByIdDesc("Invoice",id))
            if (!"Sent".equals(message.getStatus())) return response(message);
        EmailMessage message=enqueue("Invoice",id,text(buyer.get("email")),"Invoice "+number,
            "Hello "+text(buyer.get("name"))+",\n\nPlease find invoice "+number+" attached as a PDF."
            +"\nDue date: "+text(invoice.get("dueDate"))+"\nCurrency: SAR\n\nThank you for your business.",
            pdf.render(invoice),number+".pdf");
        return response(message);
    }
    public void bookingEscalation(long bookingId,String recipient,String reference,String issue) { enqueue("BookingAlert",bookingId,recipient,"Urgent booking follow-up: "+reference,issue+"\nBooking: "+reference+"\nSign in to Rental Admin and review the Booking Attention Queue. Acknowledgement does not resolve the booking issue.",null,null); }
    public void passwordReset(long accountId,String recipient,String url) {
        enqueue("Auth",accountId,recipient,"Reset your Rental Admin password","Open this link to reset your password (valid for 30 minutes):\n"+url+"\n\nIf you did not request this, ignore this email.",null,null);
    }
    private EmailMessage enqueue(String kind,long id,String recipient,String subject,String body,byte[] attachment,String name) {
        if (recipient.length()>254 || !recipient.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"A valid recipient email is required.");
        EmailMessage email=new EmailMessage(); email.setRecordKind(kind); email.setRecordId(id); email.setRecipient(recipient);
        email.setSubject(subject); email.setBody(body); email.setAttachment(attachment==null?null:Base64.getEncoder().encodeToString(attachment));
        email.setAttachmentName(name); email.setStatus("Queued"); email.setCreatedAt(Instant.now()); email.setNextAttemptAt(Instant.now());
        return emails.save(email);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(String kind,long id) {
        if (!Set.of("Individual","Corporate","Invoice").contains(kind)) throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid email record type.");
        return Map.of("configured",enabled,"messages",emails.findByRecordKindAndRecordIdOrderByIdDesc(kind,id).stream().map(this::response).toList());
    }
    @Transactional
    public Map<String,Object> retry(long id) {
        EmailMessage email=emails.lockById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Email not found."));
        if ("Auth".equals(email.getRecordKind())) throw new ApiException(HttpStatus.FORBIDDEN,"Request a new reset link from the sign-in page.");
        if (!"Failed".equals(email.getStatus())) throw new ApiException(HttpStatus.CONFLICT,"Only failed emails can be retried.");
        email.setStatus("Queued"); email.setAttempts(0); email.setNextAttemptAt(Instant.now()); email.setLastError(null);
        return response(email);
    }
    private Map<String,Object> response(EmailMessage e) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",e.getId()); out.put("recipient",e.getRecipient()); out.put("subject",e.getSubject()); out.put("status",e.getStatus());
        out.put("attempts",e.getAttempts()); out.put("createdAt",e.getCreatedAt().toString());
        out.put("sentAt",e.getSentAt()==null?null:e.getSentAt().toString()); out.put("error",e.getLastError());
        return out;
    }
    private String text(Object value) { return value==null?"":String.valueOf(value).trim(); }
}
