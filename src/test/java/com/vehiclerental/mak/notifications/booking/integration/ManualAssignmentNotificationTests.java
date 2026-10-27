package com.vehiclerental.mak.notifications.booking.integration;

import com.vehiclerental.mak.notifications.booking.service.BookingNotificationService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @Transactional
class ManualAssignmentNotificationTests {
 @Autowired BookingNotificationService notifications;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 final LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Riyadh"));
 long rental(String kind,String status,LocalDateTime pickup,LocalDateTime alert){String ref=UUID.randomUUID().toString();jdbc.update("insert into rental_records(reference,kind,booking_type,status,pickup_at,return_at,total_amount,received_amount,refunded_amount,payload,created_at) values(?,?,'Advance',?,?,?,100,0,0,?,?)",ref,kind,status,pickup,pickup.plusDays(1),json.writeValueAsString(Map.of("driverMode","Self-drive","serviceMode","Self-drive","assignmentAlertAt",alert.toString())),Timestamp.from(Instant.now()));return jdbc.queryForObject("select id from rental_records where reference=?",Long.class,ref);}
 long count(long id,String type){return jdbc.queryForObject("select count(*) from booking_alerts where rental_id=? and type=? and resolved_at is null",Long.class,id,type);}
 long latest(long id,String type){return jdbc.queryForObject("select max(id) from booking_alerts where rental_id=? and type=?",Long.class,id,type);}
 void alertTime(long id,LocalDateTime value){jdbc.update("update rental_records set payload=? where id=?",json.writeValueAsString(Map.of("driverMode","Self-drive","serviceMode","Self-drive","assignmentAlertAt",value.toString())),id);}
 @ParameterizedTest @ValueSource(strings={"Individual","Corporate"})
 void manualTimeReplacesHeadsUpsAndTriggersEvenDaysBeforePickup(String kind){
  long future=rental(kind,"Confirmed",now.plusHours(2),now.plusHours(1));long due=rental(kind,"Confirmed",now.plusDays(3),now.minusMinutes(1));notifications.scan();notifications.scan();assertEquals(0,count(future,"Assignment"));assertEquals(1,count(due,"Assignment"));assertEquals("Critical",jdbc.queryForObject("select priority from booking_alerts where id=?",String.class,latest(due,"Assignment")));assertTrue(jdbc.queryForObject("select snapshot from booking_alerts where id=?",String.class,latest(due,"Assignment")).contains("assignmentAlertAt"));
 }
 @Test void pendingBookingGetsConfirmationAndAssignmentGuidanceAtItsDeadline(){long id=rental("Individual","Pending",now.plusDays(3),now.minusMinutes(1));notifications.scan();assertEquals(1,count(id,"Assignment"));assertTrue(jdbc.queryForObject("select title from booking_alerts where id=?",String.class,latest(id,"Assignment")).contains("Confirm booking"));jdbc.update("update rental_records set status='Confirmed' where id=?",id);notifications.scan();assertEquals(1,count(id,"Assignment"));assertFalse(jdbc.queryForObject("select title from booking_alerts where id=?",String.class,latest(id,"Assignment")).contains("Confirm booking"));}
 @Test void changingAlertResolvesOldOccurrenceAndEscalationThenStartsFresh(){
  long id=rental("Individual","Confirmed",now.plusDays(3),now.minusMinutes(1));notifications.scan();long old=latest(id,"Assignment");jdbc.update("update booking_alerts set created_at=? where id=?",Timestamp.from(Instant.now().minusSeconds(3600)),old);notifications.scan();assertEquals(1,count(id,"Escalation"));
  alertTime(id,now.plusHours(1));notifications.scan();assertEquals(0,count(id,"Assignment"));assertEquals(0,count(id,"Escalation"));
  alertTime(id,now.minusMinutes(1));notifications.scan();assertEquals(1,count(id,"Assignment"));assertNotEquals(old,latest(id,"Assignment"));assertEquals(0,count(id,"Escalation"));
 }
 @Test void cancellingOrStartingResolvesAssignmentAndStopsRecurrence(){for(String status:List.of("Cancelled","Active","Completed")){long id=rental("Corporate","Confirmed",now.plusDays(2),now.minusMinutes(1));notifications.scan();assertEquals(1,count(id,"Assignment"));jdbc.update("update rental_records set status=? where id=?",status,id);notifications.scan();notifications.scan();assertEquals(0,count(id,"Assignment"));}}
 @Test void rescheduledPickupInvalidatesOldOccurrenceEvenWhenAlertTimeIsRetained(){long id=rental("Individual","Confirmed",now.plusDays(3),now.minusMinutes(1));notifications.scan();long old=latest(id,"Assignment");jdbc.update("update rental_records set pickup_at=?,return_at=? where id=?",now.plusDays(4),now.plusDays(5),id);notifications.scan();assertEquals(1,count(id,"Assignment"));assertNotEquals(old,latest(id,"Assignment"));assertNotNull(jdbc.queryForObject("select resolved_at from booking_alerts where id=?",Timestamp.class,old));}
}
