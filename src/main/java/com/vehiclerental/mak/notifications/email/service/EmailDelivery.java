package com.vehiclerental.mak.notifications.email.service;

import com.vehiclerental.mak.notifications.email.entity.EmailMessage;
import com.vehiclerental.mak.auth.service.RoleService;
import com.vehiclerental.mak.notifications.booking.service.BookingNotificationAudience;
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
    private final RoleService roles;
    public EmailDelivery(EmailRepository emails,ObjectProvider<JavaMailSender> sender,org.springframework.jdbc.core.JdbcTemplate jdbc,RoleService roles,@Value("${app.mail.from:}") String from) {
        this.emails=emails; this.jdbc=jdbc; this.sender=sender; this.roles=roles; this.from=from;
    }
    @Transactional
    public void deliver(long id) {
        EmailMessage email=emails.lockById(id).orElse(null);
        if (email==null || "Cancelled".equals(email.getStatus()) || "Sent".equals(email.getStatus()) || email.getAttempts()>=5 || email.getNextAttemptAt().isAfter(Instant.now())) return;
        if ("BookingAlert".equals(email.getRecordKind())) {
            var alerts=jdbc.queryForList("select rental_id,kind,type,resolved_at,condition_key from booking_alerts where id=?",email.getRecordId());
            boolean obsolete=alerts.isEmpty() || alerts.getFirst().get("resolved_at")!=null;
            if(!obsolete){
                var alert=alerts.getFirst();String key=alert.get("condition_key").toString();
                Long source=null;
                if("Escalation".equals(alert.get("type")) && key.startsWith("escalation:"))try{source=Long.parseLong(key.substring(11));}catch(NumberFormatException ignored){/* Invalid parent fails closed. */}
                obsolete=source==null || jdbc.queryForObject("select count(*) from booking_alerts where id=? and rental_id=? and kind=? and resolved_at is null",Long.class,source,alert.get("rental_id"),alert.get("kind"))==0;
                if(!obsolete)obsolete=jdbc.queryForObject("select count(*) from booking_alert_recipients where alert_id=? and acknowledged_at is not null",Long.class,source)>0;
                // Match an original recipient account, not just an address that another account may now own.
                if(!obsolete)obsolete=jdbc.queryForList("select a.role,a.custom_role_id from booking_alert_recipients n join auth_accounts a on a.id=n.account_id where n.alert_id=? and a.email=? and a.status='Active'",email.getRecordId(),email.getRecipient()).stream().noneMatch(account->BookingNotificationAudience.eligible(roles,alert.get("kind").toString(),account.get("role").toString(),account.get("custom_role_id") instanceof Number n?n.longValue():null,true));
            }
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
