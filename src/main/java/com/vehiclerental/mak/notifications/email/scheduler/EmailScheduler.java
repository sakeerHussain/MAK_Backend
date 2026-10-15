package com.vehiclerental.mak.notifications.email.scheduler;

import com.vehiclerental.mak.notifications.email.repository.EmailRepository;
import com.vehiclerental.mak.notifications.email.service.EmailDelivery;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.*;

@Component
public class EmailScheduler {
    private final EmailRepository emails;
    private final EmailDelivery delivery;
    private final boolean enabled;
    public EmailScheduler(EmailRepository emails,EmailDelivery delivery,@Value("${app.mail.enabled:false}") boolean enabled) {
        this.emails=emails; this.delivery=delivery; this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${app.mail.poll-ms:15000}")
    public void sendPending() {
        if (!enabled) return;
        for (Long id:emails.due(Instant.now(),PageRequest.of(0,10))) delivery.deliver(id);
    }
}
