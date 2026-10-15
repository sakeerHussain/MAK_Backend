package com.vehiclerental.mak.dashboard.integration;

import com.vehiclerental.mak.dashboard.service.DashboardInsightsService;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @Transactional
class DashboardInsightsTests {
 @Autowired DashboardInsightsService insights;@Autowired JdbcTemplate jdbc;
 private final LocalDate today=LocalDate.now(ZoneId.of("Asia/Riyadh"));
 @SuppressWarnings("unchecked") private Map<String,Object> part(Map<String,Object> result,String key){return (Map<String,Object>)result.get(key);}
 private long number(Map<String,Object> map,String key){return ((Number)map.get(key)).longValue();}
 private long vehicle(){String reference=UUID.randomUUID().toString();jdbc.update("insert into vehicles(registration,vehicle_id,make,model,registration_year,type,seats,transmission,fuel_type,daily_rate,rate_currency,ownership,status,on_site,listed_for_booking,show_on_public_site,current_odometer_km,service_interval_km,last_service_km) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",reference,reference,"Toyota","Test",2024,"Sedan",5,"Automatic","Petrol",100,"SAR","Owned","Available",true,true,true,1500,1000,0);return jdbc.queryForObject("select id from vehicles where registration=?",Long.class,reference);}
 private void rental(String status,String type,LocalDateTime pickup,LocalDateTime end,Long vehicle,String mode){jdbc.update("insert into rental_records(reference,kind,booking_type,status,pickup_at,return_at,vehicle_id,total_amount,received_amount,refunded_amount,payload,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),"Individual",type,status,pickup,end,vehicle,100,0,0,"{\"customerName\":\"Test Customer\",\"driverMode\":\""+mode+"\"}",Instant.now());}
 @Test void operationsPrioritiseOverdueReturnsAndExcludeSelfDriveFromDriverAssignment(){
  var permissions=Set.of("bookings.read");var before=part(insights.build(today,7,permissions),"operations");
  rental("Active","Quick",today.minusDays(2).atTime(9,0),today.minusDays(1).atTime(9,0),null,"With driver");
  rental("Confirmed","Advance",today.atTime(9,0),today.plusDays(1).atTime(9,0),null,"With driver");
  rental("Confirmed","Advance",today.plusDays(1).atTime(9,0),today.plusDays(2).atTime(9,0),vehicle(),"Self-drive");
  rental("Cancelled","Advance",today.atTime(9,0),today.plusDays(1).atTime(9,0),null,"With driver");
  var after=part(insights.build(today,7,permissions),"operations");
  assertEquals(number(before,"overdueReturns")+1,number(after,"overdueReturns"));assertEquals(number(before,"pendingHandovers")+1,number(after,"pendingHandovers"));assertEquals(number(before,"unassignedAdvance")+1,number(after,"unassignedAdvance"));
  var items=(List<?>)after.get("items");assertEquals("Overdue return",((Map<?,?>)items.get(0)).get("action"));
 }
 @Test void financesSeparatePeriodReceiptsFromCurrentBalancesAndIgnoreOpeningBalances(){
  var permissions=Set.of("invoices.read");var before=part(insights.build(today,7,permissions),"financial");String reference=UUID.randomUUID().toString();
  jdbc.update("insert into invoices(number,kind,status,total_amount,paid_amount,payload,created_at,issued_at) values(?,?,?,?,?,?,?,?)",reference,"Individual","Issued",150,50,"{\"dueDate\":\""+today.minusDays(1)+"\"}",Instant.now(),Instant.now());
  long id=jdbc.queryForObject("select id from invoices where number=?",Long.class,reference);
  jdbc.update("insert into invoice_payments(invoice_id,kind,amount,paid_on,method) values(?,?,?,?,?)",id,"Payment",20,today,"Cash");jdbc.update("insert into invoice_payments(invoice_id,kind,amount,paid_on,method) values(?,?,?,?,?)",id,"Refund",5,today,"Cash");jdbc.update("insert into invoice_payments(invoice_id,kind,amount,paid_on,method) values(?,?,?,?,?)",id,"Payment",99,today,"Opening balance");
  var after=part(insights.build(today,7,permissions),"financial");
  for(var entry:Map.of("issuedValue",150,"netCollections",15,"outstandingBalance",100,"overdueBalance",100).entrySet())assertEquals(0,((BigDecimal)after.get(entry.getKey())).subtract((BigDecimal)before.get(entry.getKey())).compareTo(BigDecimal.valueOf(entry.getValue())));
  assertFalse(insights.build(today,7,Set.of("bookings.read")).containsKey("financial"));
 }
 @Test void alertsRespectExpiryWindowAndModulePermissions(){
  long id=vehicle();for(int offset:List.of(-1,30,31))jdbc.update("insert into vehicle_files(vehicle_id,kind,document_type,expiry_date,storage_key,original_name,content_type,size_bytes,created_at) values(?,?,?,?,?,?,?,?,?)",id,"Document","Insurance",today.plusDays(offset),UUID.randomUUID().toString(),"test.pdf","application/pdf",10,Instant.now());
  var result=part(insights.build(today,7,Set.of("fleet.read","maintenance.read")),"alerts");var items=(List<?>)result.get("items");
  assertTrue(items.stream().anyMatch(item->((Map<?,?>)item).get("severity").equals("Expired")));assertTrue(items.stream().anyMatch(item->((Map<?,?>)item).get("severity").equals("Due soon")));assertTrue(items.stream().anyMatch(item->((Map<?,?>)item).get("page").equals("Maintenance")));
  assertFalse(items.stream().anyMatch(item->((Map<?,?>)item).get("detail").toString().contains(today.plusDays(31).toString())));
  var restricted=insights.build(today,7,Set.of("dashboard.read"));assertFalse(restricted.containsKey("financial"));assertEquals(0,number(part(restricted,"alerts"),"total"));assertEquals(0,number(part(restricted,"operations"),"totalItems"));
 }
}
