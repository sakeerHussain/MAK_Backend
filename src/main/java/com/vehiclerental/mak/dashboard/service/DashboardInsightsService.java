package com.vehiclerental.mak.dashboard.service;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class DashboardInsightsService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public DashboardInsightsService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 private BigDecimal sum(String sql,Object... args){var value=jdbc.queryForObject(sql,BigDecimal.class,args);return value==null?BigDecimal.ZERO:value;}
 private LocalDateTime time(Object value){return ((java.sql.Timestamp)value).toLocalDateTime();}
 private Map<String,Object> alert(String title,String detail,String page,long id,String severity){return Map.of("title",title,"detail",detail,"page",page,"id",id,"severity",severity);}
 public Map<String,Object> build(LocalDate today,int days,Set<String> permissions){
  var kinds=new ArrayList<String>();if(permissions.contains("bookings.read"))kinds.add("Individual");if(permissions.contains("corporate-trips.read"))kinds.add("Corporate");
  String kindWhere=kinds.isEmpty()?"1=0":"kind in ("+String.join(",",Collections.nCopies(kinds.size(),"?"))+")";
  var now=LocalDateTime.now(ZoneId.of("Asia/Riyadh"));var until=today.plusDays(1).atStartOfDay();var horizon=today.plusDays(7).atStartOfDay();
  long pendingHandovers=0,returnsToday=0,overdueReturns=0,unassigned=0;var items=new ArrayList<Map<String,Object>>();
  if(!kinds.isEmpty()){
   var args=new ArrayList<Object>(kinds);args.add(horizon);
   for(var row:jdbc.queryForList("select id,reference,kind,booking_type,status,pickup_at,return_at,vehicle_id,driver_id,payload from rental_records where "+kindWhere+" and status in ('Confirmed','Active') and pickup_at<?",args.toArray())){
    String status=(String)row.get("status"),kind=(String)row.get("kind");var pickup=time(row.get("pickup_at"));var end=time(row.get("return_at"));var payload=json.readTree((String)row.get("payload"));
    boolean selfDrive="Self-drive".equals(payload.path(kind.equals("Corporate")?"serviceMode":"driverMode").asText());
    boolean missingVehicle=row.get("vehicle_id")==null,missingDriver=!selfDrive && row.get("driver_id")==null;
    boolean todayPickup=pickup.toLocalDate().equals(today),overdue=status.equals("Active") && end.isBefore(now),todayReturn=status.equals("Active") && end.toLocalDate().equals(today),assignment=status.equals("Confirmed") && "Advance".equals(row.get("booking_type")) && !pickup.toLocalDate().isBefore(today) && (missingVehicle||missingDriver);
    if(status.equals("Confirmed") && todayPickup)pendingHandovers++;
    if(todayReturn)returnsToday++;if(overdue)overdueReturns++;if(assignment)unassigned++;
    String action=null;int priority=9;
    if(overdue){action="Overdue return";priority=0;}else if(assignment && todayPickup){action="Assign before pickup";priority=1;}else if(status.equals("Confirmed") && pickup.isBefore(until)){action=todayPickup?"Pending handover":"Overdue pickup";priority=2;}else if(todayReturn){action="Return due today";priority=3;}else if(assignment){action="Upcoming assignment";priority=4;}
    if(action!=null){var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("reference",row.get("reference"));item.put("kind",kind);item.put("customer",payload.path(kind.equals("Corporate")?"companyName":"customerName").asText("Customer"));item.put("pickupAt",pickup.toString());item.put("returnAt",end.toString());item.put("action",action);item.put("priority",priority);item.put("vehicleAssigned",!missingVehicle);item.put("driver",selfDrive?"Self-drive":missingDriver?"Unassigned":"Assigned");items.add(item);}
   }
  }
  items.sort(Comparator.<Map<String,Object>>comparingInt(row->((Number)row.get("priority")).intValue()).thenComparing(row->row.get(((Number)row.get("priority")).intValue()==0?"returnAt":"pickupAt").toString()));
  var operations=Map.of("pendingHandovers",pendingHandovers,"returnsToday",returnsToday,"overdueReturns",overdueReturns,"unassignedAdvance",unassigned,"items",items.stream().limit(12).toList(),"totalItems",items.size(),"assignmentDays",7);
  var alerts=new ArrayList<Map<String,Object>>();var warningUntil=today.plusDays(30);
  if(permissions.contains("fleet.read")){
   for(var row:jdbc.queryForList("select f.vehicle_id,v.registration,f.document_type,f.expiry_date from vehicle_files f join vehicles v on v.id=f.vehicle_id where f.expiry_date<=?",warningUntil)){
    var expiry=((java.sql.Date)row.get("expiry_date")).toLocalDate();alerts.add(alert("Vehicle document / "+row.get("registration"),row.get("document_type")+" expires "+expiry,"Fleet",((Number)row.get("vehicle_id")).longValue(),expiry.isBefore(today)?"Expired":"Due soon"));
   }
  }
  if(permissions.contains("drivers.read")){
   var seen=new HashSet<String>();
   for(var row:jdbc.queryForList("select id,name,iqama_expiry,licence_expiry,passport_expiry,contract_expiry,insurance_expiry from drivers where archived=false and status='Active'")){
    for(String field:List.of("iqama_expiry","licence_expiry","passport_expiry","contract_expiry","insurance_expiry"))if(row.get(field)!=null){var expiry=((java.sql.Date)row.get(field)).toLocalDate();if(!expiry.isAfter(warningUntil)){String type=field.replace("_expiry","");seen.add(row.get("id")+":"+type+":"+expiry);alerts.add(alert("Driver / "+row.get("name"),type+" expires "+expiry,"Driver",((Number)row.get("id")).longValue(),expiry.isBefore(today)?"Expired":"Due soon"));}}
   }
   for(var row:jdbc.queryForList("select f.driver_id,d.name,f.document_type,f.certificate_name,f.expiry_date from driver_files f join drivers d on d.id=f.driver_id where d.archived=false and d.status='Active' and f.expiry_date<=?",warningUntil)){
    var expiry=((java.sql.Date)row.get("expiry_date")).toLocalDate();String type=Objects.toString(row.get("certificate_name"),Objects.toString(row.get("document_type"),"Document"));String key=row.get("driver_id")+":"+type.toLowerCase().replace(" copy","").replace("license","licence")+":"+expiry;
    if(seen.add(key))alerts.add(alert("Driver / "+row.get("name"),type+" expires "+expiry,"Driver",((Number)row.get("driver_id")).longValue(),expiry.isBefore(today)?"Expired":"Due soon"));
   }
  }
  if(permissions.contains("maintenance.read") && permissions.contains("fleet.read")){
   for(var row:jdbc.queryForList("select id,registration,current_odometer_km,service_interval_km,last_service_km,oil_interval_km,last_oil_km,tyre_interval_km,last_tyre_km,brake_interval_km,last_brake_km from vehicles where status<>'Inactive'")){
    if(row.get("current_odometer_km")==null)continue;long current=((Number)row.get("current_odometer_km")).longValue();
    for(String type:List.of("service","oil","tyre","brake")){Object interval=row.get(type+"_interval_km"),last=row.get("last_"+type+"_km");if(interval==null || last==null || ((Number)interval).longValue()<=0)continue;long due=((Number)last).longValue()+((Number)interval).longValue();if(current>=due)alerts.add(alert("Maintenance / "+row.get("registration"),type+" due at "+due+" km; current "+current+" km","Maintenance",((Number)row.get("id")).longValue(),"Due"));}
   }
   for(var row:jdbc.queryForList("select j.vehicle_id,v.registration,j.next_service_date from maintenance_jobs j join vehicles v on v.id=j.vehicle_id where j.status='Completed' and j.next_service_date<=? and not exists(select 1 from maintenance_jobs newer where newer.vehicle_id=j.vehicle_id and newer.status='Completed' and newer.id>j.id)",today))alerts.add(alert("Service schedule / "+row.get("registration"),"Next service date "+row.get("next_service_date"),"Maintenance",((Number)row.get("vehicle_id")).longValue(),"Due"));
  }
  if(permissions.contains("inspections.read"))for(var row:jdbc.queryForList("select i.id,i.inspection_id,i.vehicle_id,i.vehicle_condition,i.inspection_date from inspections i where i.vehicle_condition in ('Poor','Damaged') and not exists(select 1 from inspections newer where newer.vehicle_id=i.vehicle_id and (newer.inspection_date>i.inspection_date or (newer.inspection_date=i.inspection_date and newer.id>i.id)))"))alerts.add(alert("Inspection / "+row.get("inspection_id"),"Latest recorded condition: "+row.get("vehicle_condition")+" ("+row.get("inspection_date")+")","Inspections",((Number)row.get("id")).longValue(),"Review"));
  if(permissions.contains("invoices.read") && !kinds.isEmpty()){
   long unbilled=jdbc.queryForObject("select count(*) from rental_records r where "+kindWhere+" and status='Completed' and not exists(select 1 from invoice_sources s join invoices i on i.id=s.invoice_id where s.rental_id=r.id and i.status<>'Voided')",Long.class,kinds.toArray());
   if(unbilled>0)alerts.add(alert("Completed work awaiting billing",unbilled+" completed bookings/trips have no invoice draft or issued invoice.","Billings",0,"Review"));
  }
  alerts.sort(Comparator.comparingInt(row->switch(row.get("severity").toString()){case "Expired"->0;case "Due"->1;case "Review"->2;default->3;}));
  var result=new LinkedHashMap<String,Object>();result.put("operations",operations);result.put("alerts",Map.of("total",alerts.size(),"expired",alerts.stream().filter(row->row.get("severity").equals("Expired")).count(),"dueSoon",alerts.stream().filter(row->row.get("severity").equals("Due soon")).count(),"items",alerts.stream().limit(12).toList(),"expiryWindowDays",30));
  if(permissions.contains("invoices.read")){
   var start=today.minusDays(days-1);var zone=ZoneId.of("Asia/Riyadh");
   var issued=sum("select coalesce(sum(total_amount),0) from invoices where status='Issued' and issued_at>=? and issued_at<?",java.sql.Timestamp.from(start.atStartOfDay(zone).toInstant()),java.sql.Timestamp.from(until.atZone(zone).toInstant()));
   var payments=sum("select coalesce(sum(case when p.kind='Refund' then -p.amount else p.amount end),0) from invoice_payments p join invoices i on i.id=p.invoice_id where i.status='Issued' and (p.method is null or p.method<>'Opening balance') and p.paid_on>=? and p.paid_on<=?",start,today);
   BigDecimal outstanding=BigDecimal.ZERO,overdue=BigDecimal.ZERO;
   for(var row:jdbc.queryForList("select total_amount,paid_amount,payload from invoices where status='Issued' and total_amount>paid_amount")){
    var balance=((BigDecimal)row.get("total_amount")).subtract((BigDecimal)row.get("paid_amount"));outstanding=outstanding.add(balance);String due=json.readTree((String)row.get("payload")).path("dueDate").asText();if(!due.isBlank() && LocalDate.parse(due).isBefore(today))overdue=overdue.add(balance);
   }
   result.put("financial",Map.of("issuedValue",issued,"netCollections",payments,"outstandingBalance",outstanding,"overdueBalance",overdue,"currency","SAR"));
  }
  return result;
 }
}
