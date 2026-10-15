package com.vehiclerental.mak.config;

import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.security.AuthRateLimitFilter;
import com.vehiclerental.mak.auth.security.Permissions;
import com.vehiclerental.mak.auth.security.SessionAccountFilter;
import com.vehiclerental.mak.auth.service.AccountService;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
 @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
 @Bean org.springframework.security.core.userdetails.UserDetailsService databaseUsers(AccountRepository accounts){return username->{var account=accounts.findByEmail(username.toLowerCase(java.util.Locale.ROOT)).orElseThrow(()->new org.springframework.security.core.userdetails.UsernameNotFoundException("Account not found."));return org.springframework.security.core.userdetails.User.withUsername(account.getEmail()).password(account.getPasswordHash()).authorities(Permissions.forRole(account.getRole()).toArray(String[]::new)).disabled(!account.getStatus().equals("Active")).build();};}
 @Bean HttpSessionSecurityContextRepository contextRepository(){return new HttpSessionSecurityContextRepository();}
 @Bean HttpSessionCsrfTokenRepository csrfRepository(){return new HttpSessionCsrfTokenRepository();}
 @Bean SecurityFilterChain security(HttpSecurity http,AccountService accounts,EmailRepository emails,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf,
 @Value("${app.auth.rate-limit:40}") int rateLimit)throws Exception{
  http.cors(cors->{}).csrf(config->config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
   .securityContext(config->config.securityContextRepository(contexts).requireExplicitSave(true))
   .requestCache(cache->cache.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable())
   .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->{response.setStatus(401);response.setContentType("application/problem+json");response.getWriter().write("{\"detail\":\"Your session has expired. Please sign in.\"}");})
    .accessDeniedHandler((request,response,error)->{response.setStatus(403);response.setContentType("application/problem+json");response.getWriter().write("{\"detail\":\"You do not have permission, or the security token is missing. Refresh and try again.\"}");}))
   .logout(logout->logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true).clearAuthentication(true).deleteCookies("RENTAL_SESSION")
    .logoutSuccessHandler((request,response,authentication)->{if(authentication!=null && authentication.getPrincipal() instanceof AccountPrincipal principal)accounts.audit(principal.id(),principal.id(),"SIGNED_OUT","Session signed out");response.setContentType("application/json");response.getWriter().write("{}");}))
   .addFilterAfter(new SessionAccountFilter(accounts),SecurityContextHolderFilter.class)
   .addFilterBefore(new AuthRateLimitFilter(rateLimit),UsernamePasswordAuthenticationFilter.class);
  http.authorizeHttpRequests(rules->{
   rules.requestMatchers("/error").permitAll();
   rules.requestMatchers("/api/notifications","/api/notifications/**").access((authentication,context)->new AuthorizationDecision(allowed(accounts,authentication.get(),"bookings.write") || allowed(accounts,authentication.get(),"corporate-trips.write")));
   rules.requestMatchers(HttpMethod.GET,"/api/auth/config","/api/auth/csrf","/api/auth/me").permitAll();
   rules.requestMatchers(HttpMethod.POST,"/api/auth/signup","/api/auth/login","/api/auth/forgot-password","/api/auth/reset-password").permitAll();
   rules.requestMatchers("/api/users","/api/users/**").access(permission(accounts,"users.manage"));
   rules.requestMatchers(HttpMethod.GET,"/api/dashboard").access(permission(accounts,"dashboard.read"));
    rules.requestMatchers(HttpMethod.GET,"/api/security-audit").access(permission(accounts,"audit.read"));
   for(String module:Permissions.MODULES){String resource=module.equals("fleet")?"vehicles":module;rules.requestMatchers(HttpMethod.GET,"/api/"+resource,"/api/"+resource+"/**").access(permission(accounts,module+".read"));rules.requestMatchers(HttpMethod.HEAD,"/api/"+resource,"/api/"+resource+"/**").access(permission(accounts,module+".read"));rules.requestMatchers("/api/"+resource,"/api/"+resource+"/**").access(permission(accounts,module+".write"));}
   rules.requestMatchers(HttpMethod.GET,"/api/quotes","/api/quotes/**").access(permission(accounts,"customers.read"));
   rules.requestMatchers("/api/quotes","/api/quotes/**").access(permission(accounts,"customers.write"));
   rules.requestMatchers("/api/emails","/api/emails/**").access((authentication,context)->{
    String kind=context.getRequest().getParameter("kind");
    if(!context.getRequest().getMethod().equals("GET")){try{String[] parts=context.getRequest().getRequestURI().split("/");kind=emails.findById(Long.parseLong(parts[3])).map(e->e.getRecordKind()).orElse(null);}catch(RuntimeException ignored){kind=null;}}
    String module="Individual".equals(kind)?"bookings":"Corporate".equals(kind)?"corporate-trips":"Invoice".equals(kind)?"invoices":null;
    return new AuthorizationDecision(module!=null && allowed(accounts,authentication.get(),module+(context.getRequest().getMethod().equals("GET")?".read":".write")));
   });
   rules.anyRequest().denyAll();
  });return http.build();
 }
 private AuthorizationManager<RequestAuthorizationContext> permission(AccountService accounts,String permission){return (authentication,context)->new AuthorizationDecision(allowed(accounts,authentication.get(),permission));}
 private boolean allowed(AccountService accounts,org.springframework.security.core.Authentication authentication,String permission){
  if(authentication==null || !(authentication.getPrincipal() instanceof AccountPrincipal principal))return false;var account=accounts.active(principal);return account!=null && Permissions.forRole(account.getRole()).contains(permission);
 }
}
