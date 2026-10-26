package com.vehiclerental.mak.notifications.booking.sse;

import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.security.Permissions;
import com.vehiclerental.mak.auth.service.AccountService;
import com.vehiclerental.mak.notifications.booking.service.BookingNotificationService;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class BookingNotificationStream {
 private static final Logger log=LoggerFactory.getLogger(BookingNotificationStream.class);
 private final BookingNotificationService notifications;
 private final AccountService accounts;
 private final Map<SseEmitter,Connection> connections=new ConcurrentHashMap<>();
 private static class Connection {final AccountPrincipal principal;final HttpSession session;long cursor;long heartbeat;
  Connection(AccountPrincipal principal,HttpSession session,long cursor){this.principal=principal;this.session=session;this.cursor=cursor;this.heartbeat=System.currentTimeMillis();}}
 public BookingNotificationStream(BookingNotificationService notifications,AccountService accounts){this.notifications=notifications;this.accounts=accounts;}
 public SseEmitter connect(AccountPrincipal principal,HttpSession session){
  var emitter=new SseEmitter(300_000L);var connection=new Connection(principal,session,notifications.cursor(principal.id()));connections.put(emitter,connection);
  emitter.onCompletion(()->connections.remove(emitter));emitter.onTimeout(()->{connections.remove(emitter);emitter.complete();});emitter.onError(error->connections.remove(emitter));
  try{emitter.send(SseEmitter.event().name("ready").id(Long.toString(connection.cursor)).reconnectTime(3000).data(Map.of("cursor",connection.cursor)));}catch(Exception error){connections.remove(emitter);emitter.complete();}
  return emitter;
 }
 @Scheduled(fixedDelayString="${app.notifications.dispatch-ms:2000}")
 public void dispatch(){
  for(var entry:connections.entrySet()){
   var emitter=entry.getKey();var c=entry.getValue();
   try{
    var account=accounts.active(c.principal);
    boolean expired=c.session.getMaxInactiveInterval()>0 && System.currentTimeMillis()-c.session.getLastAccessedTime()>c.session.getMaxInactiveInterval()*1000L;
    if(account==null || expired || (!com.vehiclerental.mak.auth.security.RolePolicy.operates(accounts.permissions(account),"bookings")&&!com.vehiclerental.mak.auth.security.RolePolicy.operates(accounts.permissions(account),"corporate-trips"))){emitter.send(SseEmitter.event().name("expired").data("Session expired"));emitter.complete();connections.remove(emitter);continue;}
    long cursor=notifications.cursor(c.principal.id());
    if(cursor!=c.cursor){emitter.send(SseEmitter.event().name("changed").id(Long.toString(cursor)).data(Map.of("cursor",cursor)));c.cursor=cursor;}
    else if(System.currentTimeMillis()-c.heartbeat>15000){emitter.send(SseEmitter.event().comment("heartbeat "+Instant.now()));c.heartbeat=System.currentTimeMillis();}
   }catch(Exception error){connections.remove(emitter);emitter.complete();}
  }
 }
 @Scheduled(fixedDelayString="${app.notifications.scan-ms:30000}",initialDelayString="${app.notifications.scan-initial-ms:10000}")
 public void reminders(){try{notifications.scan();}catch(Exception error){log.error("Booking notification check failed; the next scheduled check will retry",error);}}
}
