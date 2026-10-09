package com.vehiclerental.mak.dashboard;
import com.vehiclerental.mak.auth.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestController @RequestMapping("/api/dashboard")
public class DashboardController {
 private final DashboardService dashboard; private final AccountService accounts;
 public DashboardController(DashboardService dashboard,AccountService accounts){this.dashboard=dashboard;this.accounts=accounts;}
 @GetMapping public Map<String,Object> summary(@RequestParam(defaultValue="7") int days,Authentication authentication){
  if(days!=7 && days!=30 && days!=90)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose 7, 30 or 90 days.");
  var account=accounts.active((AccountPrincipal)authentication.getPrincipal());
  if(account==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  return dashboard.summary(days,Permissions.forRole(account.getRole()));
 }
}
