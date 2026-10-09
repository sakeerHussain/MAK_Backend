package com.vehiclerental.mak.auth;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class UserController {
 private final AccountService accounts;
 public UserController(AccountService accounts){this.accounts=accounts;}
 @GetMapping("/api/users") public List<Map<String,Object>> list(){return accounts.list();}
 @GetMapping("/api/users/roles") public Map<String,Set<String>> roles(){return Permissions.ROLES;}
 @PatchMapping("/api/users/{id}/access") public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody AuthRequests.Access access,Authentication authentication){return accounts.update(((AccountPrincipal)authentication.getPrincipal()).id(),id,access);}
 @GetMapping("/api/security-audit") public List<AuthAudit> audit(){return accounts.auditLog();}
}
