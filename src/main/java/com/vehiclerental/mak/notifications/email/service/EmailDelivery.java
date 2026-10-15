package com.vehiclerental.mak.notifications.email.service;

import com.vehiclerental.mak.notifications.email.entity.EmailMessage;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;

import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailDelivery {
    private final EmailRepository emails;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    public EmailDelivery(EmailRepository emails,ObjectProvider<JavaMailSender> sender,org.springframework.jdbc.core.JdbcTemplate jdbc,@Value("${app.mail.from:}") String from) {
        this.emails=emails; this.jdbc=jdbc; this.sender=sender; this.from=from;
    }
    @Transactional
    public void deliver(long id) {
        EmailMessage email=emails.lockById(id).orElse(null);
        if (email==null || "Cancelled".equals(email.getStatus()) || "Sent".equals(email.getStatus()) || email.getAttempts()>=5 || email.getNextAttemptAt().isAfter(Instant.now())) return;
        if ("BookingAlert".equals(email.getRecordKind())) {
            var alerts=jdbc.queryForList("select resolved_at,alert_key from booking_alerts where id=?",email.getRecordId());
            boolean obsolete=alerts.isEmpty() || alerts.getFirst().get("resolved_at")!=null;
            if(!obsolete){String key=alerts.getFirst().get("alert_key").toString();obsolete=!key.startsWith("escalation:") || jdbc.queryForObject("select count(*) from booking_alert_recipients where alert_id=? and acknowledged_at is not null",Long.class,Long.parseLong(key.substring(11)))>0;}
            obsolete=obsolete || jdbc.queryForObject("select count(*) from auth_accounts where email=? and status='Active' and role in ('Admin','Manager')",Long.class,email.getRecipient())==0;
            if(obsolete){email.setStatus("Cancelled");email.setLastError("Booking alert resolved, acknowledged or recipient access changed before delivery.");return;}
        }
        email.setAttempts(email.getAttempts()+1);
        try {
            JavaMailSender mail=sender.getIfAvailable();
            if (mail==null || from.isBlank()) throw new IllegalStateException("SMTP not configured");
            var message=mail.createMimeMessage();
            var helper=new MimeMessageHelper(message,email.getAttachment()!=null,"UTF-8");
            helper.setFrom(from); helper.setTo(email.getRecipient()); helper.setSubject(email.getSubject()); helper.setText(email.getBody(),false);
            if (email.getAttachment()!=null) helper.addAttachment(email.getAttachmentName(),new ByteArrayResource(Base64.getDecoder().decode(email.getAttachment())),"application/pdf");
            mail.send(message);
            email.setStatus("Sent"); email.setSentAt(Instant.now()); email.setLastError(null);
        } catch (Exception e) {
            // Do not expose provider responses, credentials or message contents to the UI/logs.
            email.setStatus("Failed"); email.setLastError("Delivery failed. Check SMTP settings or recipient and retry.");
            email.setNextAttemptAt(Instant.now().plusSeconds(60L*(1L<<Math.min(email.getAttempts(),5))));
        }
    }
}
