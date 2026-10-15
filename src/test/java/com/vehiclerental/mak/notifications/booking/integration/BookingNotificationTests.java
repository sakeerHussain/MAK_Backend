package com.vehiclerental.mak.notifications.booking.integration;

import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.security.Permissions;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.notifications.booking.service.BookingNotificationService;
import com.vehiclerental.mak.notifications.booking.sse.BookingNotificationStream;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;
import com.vehiclerental.mak.notifications.email.service.EmailDelivery;
import com.vehiclerental.mak.notifications.email.service.EmailService;
import com.vehiclerental.mak.rentals.entity.RentalRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest @Transactional
class BookingNotificationTests {
 @Autowired EmailDelivery delivery;@Autowired EmailRepository emailRepository;@Autowired BookingNotificationStream broker;@Autowired jakarta.persistence.EntityManager entityManager;
 @Autowired BookingNotificationService service;@Autowired JdbcTemplate jdbc;@Autowired WebApplicationContext context;@Autowired PlatformTransactionManager transactionManager;@Autowired EmailService emails;
 private static final Set<String> WRITE=Set.of("bookings.write");
 private final LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Riyadh"));
 private long account(String role){String email=UUID.randomUUID()+"@example.com";jdbc.update("insert into auth_accounts(name,email,password_hash,role,status,created_at) values(?,?,?,?,?,?)","Test",email,"unused",role,"Active",Timestamp.from(Instant.now()));return jdbc.queryForObject("select id from auth_accounts where email=?",Long.class,email);}
 private long rental(String kind,String status,LocalDateTime pickup,String mode){String ref=UUID.randomUUID().toString();jdbc.update("insert into rental_records(reference,kind,booking_type,status,pickup_at,return_at,total_amount,received_amount,refunded_amount,payload,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",ref,kind,"Advance",status,pickup,pickup.plusDays(1),100,0,0,"{\"customerName\":\"Test\",\"companyName\":\"Company\",\"driverMode\":\""+mode+"\",\"serviceMode\":\""+mode+"\"}",Timestamp.from(Instant.now()));return jdbc.queryForObject("select id from rental_records where reference=?",Long.class,ref);}
 private long alert(long rental,String type){return jdbc.queryForObject("select id from booking_alerts where rental_id=? and type=? order by id desc limit 1",Long.class,rental,type);}
 private void event(long id,String type){var r=new RentalRecord();r.setId(id);service.bookingChanged(r,type);}
 private long count(long rental,String type){return jdbc.queryForObject("select count(*) from booking_alerts where rental_id=? and type=?",Long.class,rental,type);}
 private MockHttpSession session(long id,String role){var session=new MockHttpSession();var authentication=UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(id,0),null,Permissions.forRole(role).stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList());session.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(authentication));return session;}
 @Test void remindersDeduplicateAndAcknowledgementDoesNotResolve(){
  long account=account("Operations"),rental=rental("Individual","Confirmed",now.minusMinutes(2),"With driver");service.scan();service.scan();assertEquals(1,count(rental,"Missed handover"));long id=alert(rental,"Missed handover");service.mark(account,WRITE,id,true);assertNull(jdbc.queryForObject("select resolved_at from booking_alerts where id=?",Timestamp.class,id));assertNotNull(jdbc.queryForObject("select acknowledged_at from booking_alert_recipients where alert_id=? and account_id=?",Timestamp.class,id,account));jdbc.update("update rental_records set status='Active' where id=?",rental);service.scan();assertNotNull(jdbc.queryForObject("select resolved_at from booking_alerts where id=?",Timestamp.class,id));
 }
 @Test void selfDriveOnlyNeedsVehicleAndCorporateNeedsDriver(){
  account("Admin");long r=rental("Individual","Confirmed",now.plusMinutes(30),"Self-drive");long corporate=rental("Corporate","Confirmed",now.plusMinutes(30),"With driver");
  String reference=UUID.randomUUID().toString();jdbc.update("insert into vehicles(registration,vehicle_id,make,model,registration_year,type,seats,transmission,fuel_type,daily_rate,rate_currency,ownership,status,on_site,listed_for_booking,show_on_public_site) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",reference,reference,"Toyota","Test",2024,"Sedan",5,"Automatic","Petrol",100,"SAR","Owned","Available",true,true,true);long vehicle=jdbc.queryForObject("select id from vehicles where registration=?",Long.class,reference);jdbc.update("update rental_records set vehicle_id=? where id in (?,?)",vehicle,r,corporate);service.scan();assertEquals(0,count(r,"Assignment"));assertEquals(1,count(corporate,"Assignment"));
 }
 @Test void reschedulingAndCancellationResolveStaleAlerts(){long a=account("Admin"),r=rental("Individual","Confirmed",now.minusMinutes(1),"With driver");event(r,"Created");long id=alert(r,"Missed handover");jdbc.update("update rental_records set pickup_at=?,return_at=? where id=?",now.plusDays(5),now.plusDays(6),r);event(r,"Rescheduled");assertNotNull(jdbc.queryForObject("select resolved_at from booking_alerts where id=?",Timestamp.class,id));jdbc.update("update rental_records set status='Cancelled' where id=?",r);event(r,"Cancelled");assertEquals(1,count(r,"Cancelled"));assertTrue(((Number)service.summary(a,WRITE).get("unread")).longValue()>0);}
 @Test void supervisorsEscalateOnceAndEmailQueuesOnlyWhenEnabled(){long admin=account("Admin"),r=rental("Individual","Confirmed",now.minusMinutes(2),"With driver");service.scan();jdbc.update("update booking_alerts set created_at=? where rental_id=?",Timestamp.from(Instant.now().minusSeconds(3600)),r);var withEmail=new BookingNotificationService(jdbc,new tools.jackson.databind.ObjectMapper(),emails,60,120,15,true,true);withEmail.scan();long count=count(r,"Escalation");assertTrue(count>0);withEmail.scan();assertEquals(count,count(r,"Escalation"));assertTrue(jdbc.queryForObject("select count(*) from email_outbox where record_kind='BookingAlert' and record_id in(select id from booking_alerts where rental_id=?)",Long.class,r)>0);for(var row:jdbc.queryForList("select id from booking_alerts where rental_id=? and type<>'Escalation'",r))service.mark(admin,WRITE,((Number)row.get("id")).longValue(),true);service.scan();assertEquals(0,jdbc.queryForObject("select count(*) from booking_alerts where rental_id=? and type='Escalation' and resolved_at is null",Long.class,r));for(var email:jdbc.queryForList("select id from email_outbox where record_kind='BookingAlert' and record_id in(select id from booking_alerts where rental_id=?)",r)){long id=((Number)email.get("id")).longValue();delivery.deliver(id);assertEquals("Cancelled",emailRepository.findById(id).orElseThrow().getStatus());}}
 @Test void notificationsAreRecipientScopedAndReadAllPreservesOtherUsers(){long a=account("Operations"),b=account("Admin"),viewer=account("Viewer");long r=rental("Individual","Confirmed",now.plusDays(2),"With driver");event(r,"Created");long id=alert(r,"Created");assertEquals(0L,service.summary(viewer,Set.of("bookings.read")).get("unread"));assertThrows(ApiException.class,()->service.mark(viewer,WRITE,id,true));service.readAll(a,WRITE);assertEquals(0L,service.summary(a,WRITE).get("unread"));assertEquals(1L,service.summary(b,WRITE).get("unread"));assertTrue(((List<?>)service.list(b,Set.of("corporate-trips.write"),"All",null,null,null,null,null,0).get("items")).isEmpty());}
 @Test void pendingAndOverdueReturnsAreCaughtWithoutBrowser(){account("Operations");long pending=rental("Individual","Pending",now.plusMinutes(30),"Self-drive"),active=rental("Individual","Active",now.minusHours(2),"Self-drive");jdbc.update("update rental_records set return_at=? where id=?",now.minusMinutes(1),active);service.scan();assertEquals(1,count(pending,"Confirmation"));assertEquals(1,count(active,"Overdue return"));}
 @Test void endpointsEnforceAuthenticationCsrfAndStreamReady()throws Exception{MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());long viewer=account("Viewer"),admin=account("Admin");mvc.perform(get("/api/notifications").session(session(viewer,"Viewer"))).andExpect(status().isForbidden());var session=session(admin,"Admin");mvc.perform(post("/api/notifications/read-all").session(session)).andExpect(status().isForbidden());mvc.perform(post("/api/notifications/read-all").session(session).with(csrf().asHeader())).andExpect(status().isOk());var stream=mvc.perform(get("/api/notifications/stream").session(session)).andExpect(request().asyncStarted()).andReturn();assertTrue(stream.getResponse().getContentAsString().contains("event:ready"));stream.getRequest().getAsyncContext().complete();}
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void rolledBackEventsNeverReachPersistentDelivery(){long baseline=jdbc.queryForObject("select count(*) from booking_alerts",Long.class);assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).execute(status->{long r=rental("Individual","Confirmed",now.plusDays(3),"Self-drive");event(r,"Created");throw new IllegalStateException("Rollback");}));assertEquals(baseline,jdbc.queryForObject("select count(*) from booking_alerts",Long.class));}
 @Test void ssePushesCommittedCursorAndRevokesChangedSessions()throws Exception{
  long admin=account("Admin");var session=session(admin,"Admin");MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  var connection=mvc.perform(get("/api/notifications/stream").session(session)).andExpect(request().asyncStarted()).andReturn();
  long r=rental("Individual","Confirmed",now.plusDays(2),"Self-drive");event(r,"Created");broker.dispatch();assertTrue(connection.getResponse().getContentAsString().contains("event:changed"));
  long cursor=service.cursor(admin);assertTrue(cursor>0);service.mark(admin,WRITE,alert(r,"Created"),false);assertTrue(service.cursor(admin)>cursor);
  jdbc.update("update auth_accounts set session_version=session_version+1 where id=?",admin);entityManager.clear();broker.dispatch();assertTrue(connection.getResponse().getContentAsString().contains("event:expired"));
 }
}
