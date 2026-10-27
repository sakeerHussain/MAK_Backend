package com.vehiclerental.mak.notifications.booking.integration;

import com.vehiclerental.mak.auth.service.RoleService;
import com.vehiclerental.mak.notifications.booking.service.BookingNotificationService;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;
import com.vehiclerental.mak.notifications.email.service.*;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest @Transactional
class NotificationReliabilityTests {
 @Autowired BookingNotificationService notifications;
 @Autowired EmailService emails; @Autowired EmailDelivery delivery; @Autowired EmailRepository outbox;
 @Autowired JdbcTemplate jdbc; @Autowired RoleService roles; @Autowired ObjectMapper json;
 @MockitoBean JavaMailSender sender;
 LocalDateTime now; long actor;
 @BeforeEach void setup(){now=LocalDateTime.now(ZoneId.of("Asia/Riyadh"));actor=account("Admin",null);reset(sender);when(sender.createMimeMessage()).thenAnswer(invocation->new MimeMessage(Session.getInstance(new Properties())));}
 long account(String role,Long custom){String email=UUID.randomUUID()+"@example.invalid";jdbc.update("insert into auth_accounts(name,email,password_hash,role,custom_role_id,status,created_at) values(?,?,?,?,?,'Active',?)","Notification QA",email,"unused",role,custom,Timestamp.from(Instant.now()));return jdbc.queryForObject("select id from auth_accounts where email=?",Long.class,email);}
 long rental(String kind,String status,LocalDateTime pickup,LocalDateTime end){String ref="QA-"+UUID.randomUUID();jdbc.update("insert into rental_records(reference,kind,booking_type,status,pickup_at,return_at,total_amount,received_amount,refunded_amount,payload,created_at) values(?,?,'Advance',?,?,?,100,0,0,?,?)",ref,kind,status,pickup,end,"{\"customerName\":\"Test\",\"companyName\":\"Test\",\"driverMode\":\"Self-drive\",\"serviceMode\":\"Self-drive\"}",Timestamp.from(Instant.now()));return jdbc.queryForObject("select id from rental_records where reference=?",Long.class,ref);}
 long latest(long id,String type){return jdbc.queryForObject("select max(id) from booking_alerts where rental_id=? and type=?",Long.class,id,type);}
 void event(long id,String type){var record=new RentalRecord();record.setId(id);notifications.bookingChanged(record,type);}
 void age(long id){jdbc.update("update booking_alerts set created_at=? where rental_id=? and actionable=true and resolved_at is null",Timestamp.from(Instant.now().minusSeconds(3600)),id);}
 BookingNotificationService withEmail(){return new BookingNotificationService(jdbc,json,emails,roles,60,120,15,true,true);}
 String email(long account){return jdbc.queryForObject("select email from auth_accounts where id=?",String.class,account);}
 String module(String kind){return kind.equals("Corporate")?"corporate-trips":"bookings";}
 Map<String,Object> role(List<String> grants){return roles.save(null,new LinkedHashMap<>(Map.of("name","Notification QA "+UUID.randomUUID(),"permissions",grants)),actor);}
 void grants(Map<String,Object> role,List<String> grants){var changed=roles.save(((Number)role.get("id")).longValue(),new LinkedHashMap<>(Map.of("name",role.get("name"),"version",role.get("version"),"permissions",grants)),actor);role.putAll(changed);}
 // Supports historical unsuffixed keys as well as occurrence keys, so these tests reproduce the old behavior.
 long missedEmail(long rental,String recipient){return jdbc.queryForObject("select e.id from email_outbox e join booking_alerts a on a.id=e.record_id where e.record_kind='BookingAlert' and a.rental_id=? and a.type='Escalation' and (a.alert_key=? or a.alert_key like ?) and e.recipient=?",Long.class,rental,"escalation:"+latest(rental,"Missed handover"),"escalation:"+latest(rental,"Missed handover")+":%",recipient);}
 long open(long rental,String type){return jdbc.queryForObject("select count(*) from booking_alerts where rental_id=? and type=? and resolved_at is null",Long.class,rental,type);}
 void expectCancelled(long message)throws Exception{delivery.deliver(message);var stored=outbox.findById(message).orElseThrow();assertEquals("Cancelled",stored.getStatus());assertEquals(0,stored.getAttempts());assertNull(stored.getSentAt());verify(sender,never()).send(any(MimeMessage.class));}

 @ParameterizedTest @CsvSource({"Pending,Confirmation,30","Confirmed,Pickup,30","Confirmed,Missed handover,-2","Active,Overdue return,-2880"})
 void returningToTheSameScheduleCreatesFreshUnreadOccurrence(String status,String type,int minutes){
  long id=rental("Individual",status,now.plusMinutes(minutes),now.plusMinutes(minutes).plusDays(1));notifications.scan();long old=latest(id,type);notifications.mark(actor,Set.of("bookings.write"),old,true);
  var original=jdbc.queryForMap("select pickup_at,return_at from rental_records where id=?",id);
  jdbc.update("update rental_records set pickup_at=?,return_at=? where id=?",now.plusDays(5),now.plusDays(6),id);event(id,"Rescheduled");assertNotNull(jdbc.queryForMap("select resolved_at from booking_alerts where id=?",old).get("resolved_at"));
  long cursor=notifications.cursor(actor);jdbc.update("update rental_records set pickup_at=?,return_at=? where id=?",original.get("pickup_at"),original.get("return_at"),id);event(id,"Rescheduled");notifications.scan();notifications.scan();
  long fresh=latest(id,type);assertNotEquals(old,fresh,"Returning to the same schedule must create a new occurrence");assertEquals(1,open(id,type));var recipient=jdbc.queryForMap("select read_at,acknowledged_at from booking_alert_recipients where alert_id=? and account_id=?",fresh,actor);assertNull(recipient.get("read_at"));assertNull(recipient.get("acknowledged_at"));assertNotNull(jdbc.queryForMap("select acknowledged_at from booking_alert_recipients where alert_id=? and account_id=?",old,actor).get("acknowledged_at"));assertTrue(notifications.cursor(actor)>cursor);
 }
 @Test void missingAssignmentCanRecurAndItsNewCriticalOccurrenceEscalatesIndependently(){
  long id=rental("Individual","Confirmed",now.plusMinutes(30),now.plusDays(1));notifications.scan();long old=latest(id,"Assignment");age(id);withEmail().scan();long oldEscalation=latest(id,"Escalation");notifications.mark(actor,Set.of("bookings.write"),old,true);notifications.scan();
  String ref=UUID.randomUUID().toString();jdbc.update("insert into vehicles(registration,vehicle_id,make,model,registration_year,type,seats,transmission,fuel_type,daily_rate,rate_currency,ownership,status,on_site,listed_for_booking,show_on_public_site) values(?,?, 'Toyota','QA',2024,'Sedan',5,'Automatic','Petrol',100,'SAR','Owned','Available',true,true,true)",ref,ref);long vehicle=jdbc.queryForObject("select id from vehicles where registration=?",Long.class,ref);jdbc.update("update rental_records set vehicle_id=? where id=?",vehicle,id);event(id,"Assigned");assertEquals(0,open(id,"Assignment"));
  jdbc.update("update rental_records set vehicle_id=null where id=?",id);event(id,"Assigned");long fresh=latest(id,"Assignment");assertNotEquals(old,fresh);notifications.scan();assertEquals(0,open(id,"Escalation"),"New occurrence gets its own escalation grace period");age(id);withEmail().scan();withEmail().scan();assertEquals(1,open(id,"Escalation"));assertNotEquals(oldEscalation,latest(id,"Escalation"));assertEquals(1,open(id,"Assignment"));
 }
 @ParameterizedTest @CsvSource({"Individual,readOnly","Individual,wrongModule","Corporate,readOnly","Corporate,wrongModule"})
 void queuedEscalationRechecksCurrentCustomRolePermissions(String kind,String restriction)throws Exception{
  String module=module(kind);var role=role(List.of(module+".read",module+".edit"));long manager=account("Manager",((Number)role.get("id")).longValue());long id=rental(kind,"Confirmed",now.minusMinutes(2),now.plusDays(1));notifications.scan();age(id);withEmail().scan();long message=missedEmail(id,email(manager));
  String other=kind.equals("Corporate")?"bookings":"corporate-trips";grants(role,restriction.equals("readOnly")?List.of(module+".read"):List.of(other+".read",other+".edit"));expectCancelled(message);
 }
 @ParameterizedTest @CsvSource({"Individual","Corporate"})
 void authorizedCustomRoleSupervisorCanStillReceiveEscalationExactlyOnce(String kind)throws Exception{
  String module=module(kind);var role=role(List.of(module+".read",module+".edit"));long manager=account("Manager",((Number)role.get("id")).longValue());long id=rental(kind,"Confirmed",now.minusMinutes(2),now.plusDays(1));notifications.scan();age(id);withEmail().scan();long message=missedEmail(id,email(manager));delivery.deliver(message);delivery.deliver(message);assertEquals("Sent",outbox.findById(message).orElseThrow().getStatus());var capture=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(sender,times(1)).send(capture.capture());assertEquals(email(manager),capture.getValue().getAllRecipients()[0].toString());
 }
 @Test void anEmailAddressReusedByAnotherAdminDoesNotInheritAlertDelivery()throws Exception{
  long manager=account("Manager",null);String originalEmail=email(manager);long id=rental("Individual","Confirmed",now.minusMinutes(2),now.plusDays(1));notifications.scan();age(id);withEmail().scan();long message=missedEmail(id,originalEmail);jdbc.update("update auth_accounts set email=? where id=?",UUID.randomUUID()+"@example.invalid",manager);long unrelated=account("Admin",null);jdbc.update("update auth_accounts set email=? where id=?",originalEmail,unrelated);expectCancelled(message);
 }
 @ParameterizedTest @CsvSource({"Inactive","Operations"})
 void disabledOrDemotedSupervisorsCannotReceiveQueuedEmail(String change)throws Exception{
  long manager=account("Manager",null);long id=rental("Individual","Confirmed",now.minusMinutes(2),now.plusDays(1));notifications.scan();age(id);withEmail().scan();long message=missedEmail(id,email(manager));if(change.equals("Inactive"))jdbc.update("update auth_accounts set status='Inactive' where id=?",manager);else jdbc.update("update auth_accounts set role='Operations' where id=?",manager);expectCancelled(message);
 }
 @Test void revokedRecipientsAreNotQueuedAndCanBecomeEligibleAgain(){
  var role=role(List.of("bookings.read","bookings.edit"));long manager=account("Manager",((Number)role.get("id")).longValue());long id=rental("Individual","Confirmed",now.minusMinutes(2),now.plusDays(1));notifications.scan();age(id);notifications.scan();grants(role,List.of("bookings.read"));withEmail().scan();assertEquals(0,jdbc.queryForObject("select count(*) from email_outbox where record_kind='BookingAlert' and recipient=?",Long.class,email(manager)));grants(role,List.of("bookings.read","bookings.edit"));withEmail().scan();long message=missedEmail(id,email(manager));withEmail().scan();assertEquals(message,missedEmail(id,email(manager)));
 }
}
