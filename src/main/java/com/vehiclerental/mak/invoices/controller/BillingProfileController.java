package com.vehiclerental.mak.invoices.controller;
import com.vehiclerental.mak.invoices.service.BillingProfileService;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/invoices/billing-profile")
public class BillingProfileController {
 private final BillingProfileService profiles;public BillingProfileController(BillingProfileService profiles){this.profiles=profiles;}
 @GetMapping public Map<String,Object> get(){return profiles.get();}
 @PutMapping public Map<String,Object> save(@RequestBody Map<String,Object> data,Authentication authentication){return profiles.save(data,((AccountPrincipal)authentication.getPrincipal()).id());}
}
