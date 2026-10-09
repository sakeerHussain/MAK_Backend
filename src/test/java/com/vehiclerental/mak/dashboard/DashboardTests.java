package com.vehiclerental.mak.dashboard;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
@SpringBootTest @Transactional
class DashboardTests {
 @Autowired DashboardService dashboard;@Autowired JdbcTemplate jdbc;
 @SuppressWarnings("unchecked")
 @Test void countsCurrentRentalsAndDailyPickupsWithoutCancelledRecords(){
  var permissions=Set.of("bookings.read","fleet.read","invoices.read");var before=dashboard.summary(7,permissions);
  var today=LocalDate.now(ZoneId.of("Asia/Riyadh")).atTime(10,0);
  for(String status:List.of("Active","Confirmed","Completed","Cancelled"))jdbc.update("insert into rental_records(reference,kind,booking_type,status,pickup_at,return_at,total_amount,received_amount,refunded_amount,payload,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),"Individual","Quick",status,today,today.plusDays(1),100,0,0,"{\"customerName\":\"Dashboard Test\"}",Instant.now());
  var after=dashboard.summary(7,permissions);
  assertEquals(((Number)before.get("activeRentals")).longValue()+1,((Number)after.get("activeRentals")).longValue());
  assertEquals(((Number)before.get("pickupsToday")).longValue()+3,((Number)after.get("pickupsToday")).longValue());
  var oldDays=(List<Map<String,Object>>)before.get("activity");var newDays=(List<Map<String,Object>>)after.get("activity");
  assertEquals(((Number)oldDays.get(6).get("pickups")).longValue()+3,((Number)newDays.get(6).get("pickups")).longValue());
  assertEquals(((Number)oldDays.get(6).get("completed")).longValue()+1,((Number)newDays.get(6).get("completed")).longValue());
 }
 @SuppressWarnings("unchecked")
 @Test void restrictedRolesReceiveNoFinancialOrRentalDetails(){
  jdbc.update("insert into invoices(kind,status,total_amount,paid_amount,payload,created_at) values(?,?,?,?,?,?)","Individual","Issued",100,0,"{}",Instant.now());
  var restricted=dashboard.summary(30,Set.of("dashboard.read"));
  assertEquals(0L,restricted.get("activeRentals"));assertNull(restricted.get("availableVehicles"));assertTrue(((List<?>)restricted.get("recentBookings")).isEmpty());assertTrue(((List<?>)restricted.get("attention")).isEmpty());
  var finance=dashboard.summary(7,Set.of("invoices.read"));
  assertTrue(((List<Map<String,Object>>)finance.get("attention")).stream().anyMatch(row->row.get("page").equals("Invoices") && ((Number)row.get("value")).longValue()>0));
 }
}
