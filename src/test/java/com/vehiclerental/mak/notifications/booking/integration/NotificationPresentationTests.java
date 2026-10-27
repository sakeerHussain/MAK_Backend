package com.vehiclerental.mak.notifications.booking.integration;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.notifications.booking.service.BookingNotificationService;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @Transactional
class NotificationPresentationTests {
 @Autowired BookingNotificationService notifications; @Autowired JdbcTemplate jdbc;
 static final Set<String> WRITE=Set.of("bookings.write");
 long account,rental;
 @BeforeEach void setup(){
  String email=UUID.randomUUID()+"@example.invalid";jdbc.update("insert into auth_accounts(name,email,password_hash,role,status,created_at) values(?,?,?,'Operations','Active',?)","Notification presentation QA",email,"unused",Timestamp.from(Instant.now()));account=jdbc.queryForObject("select id from auth_accounts where email=?",Long.class,email);
  String reference="QA-"+UUID.randomUUID();var start=LocalDateTime.now(ZoneId.of("Asia/Riyadh")).plusDays(5);jdbc.update("insert into rental_records(reference,kind,booking_type,status,pickup_at,return_at,total_amount,received_amount,refunded_amount,payload,created_at) values(?,'Individual','Advance','Confirmed',?,?,100,0,0,?,?)",reference,start,start.plusDays(1),"{\"customerName\":\"Test\",\"driverMode\":\"Self-drive\"}",Timestamp.from(Instant.now()));rental=jdbc.queryForObject("select id from rental_records where reference=?",Long.class,reference);
 }
 long event(String type){var record=new RentalRecord();record.setId(rental);notifications.bookingChanged(record,type);return jdbc.queryForObject("select max(id) from booking_alerts where rental_id=?",Long.class,rental);}
 long row(boolean actionable,String priority,boolean resolved,Instant time){String key="QA-"+UUID.randomUUID();jdbc.update("insert into booking_alerts(rental_id,kind,alert_key,condition_key,type,priority,title,snapshot,actionable,created_at,resolved_at) values(?,'Individual',?,?,'Pickup',?,'QA notification','{}',?,?,?)",rental,key,key,priority,actionable,Timestamp.from(time),resolved?Timestamp.from(time.plusSeconds(5)):null);long id=jdbc.queryForObject("select id from booking_alerts where alert_key=?",Long.class,key);jdbc.update("insert into booking_alert_recipients(alert_id,account_id) values(?,?)",id,account);return id;}
 @SuppressWarnings("unchecked") List<Map<String,Object>> items(String filter,String priority,int page){return (List<Map<String,Object>>)notifications.list(account,WRITE,filter,null,priority,null,null,null,page).get("items");}
 List<Long> ids(List<Map<String,Object>> items){return items.stream().map(item->((Number)item.get("id")).longValue()).toList();}
 @Test void urgentOpenWorkPrecedesChronologicalHistoryRegardlessOfOldSeverity(){
  Instant time=Instant.parse("2026-10-01T00:00:00Z");
  long newestInfo=row(false,"Information",false,time.plusSeconds(100));
  long olderCritical=row(true,"Critical",false,time.plusSeconds(10));
  long newerCritical=row(true,"Critical",false,time.plusSeconds(20));
  long action=row(true,"Action required",false,time.plusSeconds(200));
  long oldResolvedCritical=row(true,"Critical",true,time);
  assertEquals(List.of(newerCritical,olderCritical,action,newestInfo,oldResolvedCritical),ids(items("All",null,0)));
  assertEquals(List.of(newerCritical,olderCritical,action),ids(items("Unresolved",null,0)));
 }
 @Test void historyUsesCreationTimeWithIdOnlyAsATieBreaker(){
  Instant time=Instant.parse("2026-10-01T00:00:00Z");long newest=row(false,"Information",false,time.plusSeconds(100)),sameTimeLow=row(false,"Information",false,time),sameTimeHigh=row(true,"Critical",true,time);assertEquals(List.of(newest,sameTimeHigh,sameTimeLow),ids(items("All",null,0)));
 }
 @Test void chronologicalOrderingRemainsStableAcrossPages(){
  Instant time=Instant.parse("2026-10-01T00:00:00Z");var expected=new ArrayList<Long>();for(int i=0;i<27;i++)expected.add(row(false,"Information",false,time.plusSeconds(i)));Collections.reverse(expected);long oldestResolved=row(true,"Critical",true,time.minusSeconds(100));expected.add(oldestResolved);var first=items("All",null,0);var second=items("All",null,1);var actual=new ArrayList<Long>(ids(first));actual.addAll(ids(second));assertEquals(25,first.size());assertEquals(expected,actual);assertEquals(28,new HashSet<>(actual).size());
 }
 @ParameterizedTest @ValueSource(strings={"Cancelled","Rescheduled"})
 void eventNoticesAreInformationalAndCannotBeAcknowledgedAsOpenWork(String type){
  long id=event(type);var notice=items("All",null,0).getFirst();assertEquals("Information",notice.get("priority"));assertEquals(false,notice.get("actionable"));assertTrue(items("Unresolved",null,0).isEmpty());assertTrue(items("Unacknowledged",null,0).isEmpty());assertTrue(items("All","Action required",0).isEmpty());assertEquals(0L,notifications.summary(account,WRITE).get("unresolved"));assertEquals(409,assertThrows(ApiException.class,()->notifications.mark(account,WRITE,id,true)).status().value());assertNull(jdbc.queryForMap("select acknowledged_at from booking_alert_recipients where alert_id=? and account_id=?",id,account).get("acknowledged_at"));notifications.mark(account,WRITE,id,false);assertEquals(0L,notifications.summary(account,WRITE).get("unread"));
 }
 @Test void onlyUnresolvedActionsCanBeAcknowledgedAndTheyRemainUnresolved(){
  long open=row(true,"Action required",false,Instant.now()),resolved=row(true,"Critical",true,Instant.now());assertEquals(409,assertThrows(ApiException.class,()->notifications.mark(account,WRITE,resolved,true)).status().value());notifications.mark(account,WRITE,open,true);notifications.mark(account,WRITE,open,true);assertEquals(List.of(open),ids(items("Unresolved",null,0)));assertTrue(items("Unacknowledged",null,0).isEmpty());assertEquals(1L,notifications.summary(account,WRITE).get("unresolved"));assertEquals(0L,notifications.summary(account,WRITE).get("critical"));
 }
}
