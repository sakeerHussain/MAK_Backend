package com.vehiclerental.mak.notifications.email.controller;

import com.vehiclerental.mak.notifications.email.service.EmailService;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/emails")
public class EmailController {
    private final EmailService service;
    public EmailController(EmailService service) { this.service=service; }
    @GetMapping public Map<String,Object> list(@RequestParam String kind,@RequestParam long recordId) { return service.list(kind,recordId); }
    @PostMapping("/{id}/retry") public Map<String,Object> retry(@PathVariable long id) { return service.retry(id); }
}
