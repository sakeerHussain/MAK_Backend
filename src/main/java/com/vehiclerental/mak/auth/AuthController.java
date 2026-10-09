package com.vehiclerental.mak.auth;
import java.time.Duration;
import java.util.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
 private final AccountService accounts;private final HttpSessionSecurityContextRepository contexts;private final HttpSessionCsrfTokenRepository csrf;private final boolean secure;
 public AuthController(AccountService accounts,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf,@Value("${server.servlet.session.cookie.secure:false}") boolean secure){this.accounts=accounts;this.contexts=contexts;this.csrf=csrf;this.secure=secure;}
 @GetMapping("/config") public Map<String,Object> configuration(){return accounts.configuration();}
 @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
 @PostMapping("/signup") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> signup(@Valid @RequestBody AuthRequests.Signup request){return accounts.signup(request);}
 @GetMapping("/me") public Map<String,Object> me(Authentication authentication){var result=new LinkedHashMap<String,Object>();var account=authentication!=null && authentication.getPrincipal() instanceof AccountPrincipal principal?accounts.active(principal):null;result.put("user",account==null?null:accounts.response(account));return result;}
 @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody AuthRequests.Login body,HttpServletRequest request,HttpServletResponse response){
  var principal=accounts.login(body);var account=accounts.active(principal);var previous=request.getSession(false);if(previous!=null)previous.invalidate();var session=request.getSession(true);session.setMaxInactiveInterval(Boolean.TRUE.equals(body.remember())?30*24*3600:30*60);
  var authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,Permissions.forRole(account.getRole()).stream().map(SimpleGrantedAuthority::new).toList());
  var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);csrf.saveToken(null,request,response);
  var cookie=ResponseCookie.from("RENTAL_SESSION",session.getId()).path("/").httpOnly(true).secure(secure || request.isSecure()).sameSite("Lax").maxAge(Boolean.TRUE.equals(body.remember())?Duration.ofDays(30):Duration.ofSeconds(-1)).build();response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());return Map.of("user",accounts.response(account));
 }
 @PostMapping("/forgot-password") public Map<String,String> forgot(@Valid @RequestBody AuthRequests.EmailRequest request){accounts.forgot(request.email());return Map.of("message","If an active account matches and email is configured, a reset link will be sent.");}
 @PostMapping("/reset-password") public Map<String,String> reset(@Valid @RequestBody AuthRequests.Reset request){accounts.reset(request);return Map.of("message","Password updated. Sign in with your new password.");}
}
