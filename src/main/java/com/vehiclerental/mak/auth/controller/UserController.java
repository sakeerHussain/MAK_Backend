package com.vehiclerental.mak.auth.controller;

import com.vehiclerental.mak.auth.dto.request.AccountAccessRequest;
import com.vehiclerental.mak.auth.dto.response.AuthAuditResponse;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.security.Permissions;
import com.vehiclerental.mak.auth.service.AccountService;

import java.util.*;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserController {
 private final com.vehiclerental.mak.auth.service.RoleService roles;private final com.vehiclerental.mak.auth.service.InvitationService invitations;
 private final AccountService accounts;
 public UserController(AccountService accounts,com.vehiclerental.mak.auth.service.RoleService roles,com.vehiclerental.mak.auth.service.InvitationService invitations){this.accounts=accounts;this.roles=roles;this.invitations=invitations;}
 @GetMapping("/api/users") public List<Map<String,Object>> list(){return accounts.list();}
 @GetMapping("/api/users/roles") public Map<String,Set<String>> roles(){return Permissions.ROLES;}
 @PatchMapping("/api/users/{id}/access") public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody AccountAccessRequest access,Authentication authentication){return accounts.update(((AccountPrincipal)authentication.getPrincipal()).id(),id,access);}
 @GetMapping("/api/users/role-definitions") public Map<String,Object> definitions(){return Map.of("roles",roles.list(),"permissions",com.vehiclerental.mak.auth.security.RolePolicy.catalog());}
 @PostMapping("/api/users/role-definitions") public Map<String,Object> createRole(@RequestBody Map<String,Object> data,Authentication authentication){return roles.save(null,data,((AccountPrincipal)authentication.getPrincipal()).id());}
 @PutMapping("/api/users/role-definitions/{id}") public Map<String,Object> saveRole(@PathVariable long id,@RequestBody Map<String,Object> data,Authentication authentication){return roles.save(id,data,((AccountPrincipal)authentication.getPrincipal()).id());}
 @GetMapping("/api/users/invitations") public Map<String,Object> invitations(){return invitations.list();}
 @PostMapping("/api/users/invitations") public Map<String,Object> invite(@RequestBody Map<String,Object> data,Authentication authentication){return invitations.invite(data,((AccountPrincipal)authentication.getPrincipal()).id());}
 @PostMapping("/api/users/invitations/{id}/{action}") public Map<String,Object> invitationAction(@PathVariable long id,@PathVariable String action,Authentication authentication){return invitations.update(id,action,((AccountPrincipal)authentication.getPrincipal()).id());}
 @GetMapping("/api/security-audit") public List<AuthAuditResponse> audit(){return accounts.auditLog();}
}
