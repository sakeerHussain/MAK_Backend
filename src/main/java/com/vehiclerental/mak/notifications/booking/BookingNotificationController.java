package com.vehiclerental.mak.notifications.booking;
import java.util.*;
import com.vehiclerental.mak.auth.*;
import com.vehiclerental.mak.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController @RequestMapping("/api/notifications")
public class BookingNotificationController {
 private final BookingNotificationService notifications;private final BookingNotificationStream stream;private final AccountService accounts;
 public BookingNotificationController(BookingNotificationService notifications,BookingNotificationStream stream,AccountService accounts){this.notifications=notifications;this.stream=stream;this.accounts=accounts;}
 private Set<String> permissions(AccountPrincipal principal){var account=accounts.active(principal);if(account==null)throw new ApiException(HttpStatus.UNAUTHORIZED,"Sign in again.");return Permissions.forRole(account.getRole());}
 @GetMapping public Map<String,Object> list(@AuthenticationPrincipal AccountPrincipal p,@RequestParam(defaultValue="All") String filter,@RequestParam(required=false) String kind,@RequestParam(required=false) String priority,@RequestParam(required=false) String type,@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(defaultValue="0") int page){if(page<0||page>100000||!Set.of("All","Unread","Unresolved","Unacknowledged").contains(filter))throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid notification filter.");return notifications.list(p.id(),permissions(p),filter,kind,priority,type,from,to,page);}
 @GetMapping("/summary") public Map<String,Object> summary(@AuthenticationPrincipal AccountPrincipal p){return notifications.summary(p.id(),permissions(p));}
 @GetMapping(value="/stream",produces="text/event-stream") public SseEmitter stream(@AuthenticationPrincipal AccountPrincipal p,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response){permissions(p);response.setHeader("Cache-Control","no-cache, no-transform");response.setHeader("X-Accel-Buffering","no");return stream.connect(p,request.getSession(false));}
 @PostMapping("/{id}/read") public Map<String,Object> read(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id){notifications.mark(p.id(),permissions(p),id,false);return Map.of();}
 @PostMapping("/{id}/acknowledge") public Map<String,Object> acknowledge(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id){notifications.mark(p.id(),permissions(p),id,true);return Map.of();}
 @PostMapping("/read-all") public Map<String,Object> readAll(@AuthenticationPrincipal AccountPrincipal p){notifications.readAll(p.id(),permissions(p));return Map.of();}
}
