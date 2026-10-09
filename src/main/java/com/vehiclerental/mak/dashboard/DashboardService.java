package com.vehiclerental.mak.dashboard;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
@Service
public class DashboardService {
 private final JdbcTemplate jdbc; private final ObjectMapper json; private final DashboardInsightsService insights;
 public DashboardService(JdbcTemplate jdbc,ObjectMapper json,DashboardInsightsService insights){this.jdbc=jdbc;this.json=json;this.insights=insights;}
 private long count(String sql,Object... args){var n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n;}
 @Transactional(readOnly=true)
 public Map<String,Object> summary(int days,Set<String> permissions){
  var today=LocalDate.now(ZoneId.of("Asia/Riyadh"));var from=today.minusDays(days-1);var until=today.plusDays(1).atStartOfDay();
  boolean fleet=permissions.contains("fleet.read"),individual=permissions.contains("bookings.read"),corporate=permissions.contains("corporate-trips.read"),maintenance=permissions.contains("maintenance.read"),finance=permissions.contains("invoices.read");
  var kinds=new ArrayList<String>();if(individual)kinds.add("Individual");if(corporate)kinds.add("Corporate");
  var placeholders=String.join(",",Collections.nCopies(kinds.size(),"?"));var kindWhere=kinds.isEmpty()?"1=0":"kind in ("+placeholders+")";
  var fleetStatus=new ArrayList<Map<String,Object>>();long available=0,total=0;
  if(fleet){for(var row:jdbc.queryForList("select status,count(*) as amount from vehicles group by status")){long value=((Number)row.get("amount")).longValue();fleetStatus.add(Map.of("name",row.get("status"),"value",value));total+=value;if("Available".equals(row.get("status")))available=value;}}
  long active=kinds.isEmpty()?0:count("select count(*) from rental_records where "+kindWhere+" and status='Active'",kinds.toArray());
  var args=new ArrayList<Object>(kinds);args.add(today.atStartOfDay());args.add(until);
  long pickups=kinds.isEmpty()?0:count("select count(*) from rental_records where "+kindWhere+" and status in ('Confirmed','Active','Completed') and pickup_at>=? and pickup_at<?",args.toArray());
  var attention=new ArrayList<Map<String,Object>>();
  if(!kinds.isEmpty())attention.add(Map.of("name","Pending bookings","value",count("select count(*) from rental_records where "+kindWhere+" and status='Pending'",kinds.toArray()),"page",individual?"Bookings":"Corporate Trips"));
  if(maintenance)attention.add(Map.of("name","Open maintenance","value",count("select count(*) from maintenance_jobs where status<>'Completed' and status<>'Cancelled'"),"page","Maintenance"));
  if(finance)attention.add(Map.of("name","Outstanding invoices","value",count("select count(*) from invoices where status='Issued' and total_amount>paid_amount"),"page","Invoices"));
  var activity=new ArrayList<Map<String,Object>>();var daily=new HashMap<LocalDate,long[]>();
  if(!kinds.isEmpty()){
   var periodArgs=new ArrayList<Object>(kinds);periodArgs.add(from.atStartOfDay());periodArgs.add(until);
   for(var row:jdbc.queryForList("select cast(pickup_at as date) as activity_date,kind,status,count(*) as amount from rental_records where "+kindWhere+" and status<>'Cancelled' and pickup_at>=? and pickup_at<? group by cast(pickup_at as date),kind,status",periodArgs.toArray())){
    LocalDate date=((java.sql.Date)row.get("activity_date")).toLocalDate();long value=((Number)row.get("amount")).longValue();var values=daily.computeIfAbsent(date,key->new long[3]);values["Individual".equals(row.get("kind"))?0:1]+=value;if("Completed".equals(row.get("status")))values[2]+=value;
   }
  }
  for(var date=from;!date.isAfter(today);date=date.plusDays(1)){var values=daily.getOrDefault(date,new long[3]);activity.add(Map.of("date",date.toString(),"individual",values[0],"corporate",values[1],"completed",values[2],"pickups",values[0]+values[1]));}
  var recent=new ArrayList<Map<String,Object>>();
  if(!kinds.isEmpty())for(var row:jdbc.queryForList("select id,reference,kind,status,pickup_at,return_at,total_amount,vehicle_id,payload from rental_records where "+kindWhere+" order by created_at desc,id desc limit 6",kinds.toArray())){
   var record=new LinkedHashMap<String,Object>();record.put("id",row.get("id"));record.put("reference",row.get("reference"));record.put("kind",row.get("kind"));record.put("status",row.get("status"));record.put("pickupAt",row.get("pickup_at").toString());record.put("returnAt",row.get("return_at").toString());record.put("totalAmount",row.get("total_amount"));
   var payload=json.readTree((String)row.get("payload"));record.put("customer",payload.path("Corporate".equals(row.get("kind"))?"companyName":"customerName").asText("Customer"));
   record.put("vehicle","Unassigned");if(fleet && row.get("vehicle_id")!=null){var vehicles=jdbc.queryForList("select make,model,registration from vehicles where id=?",row.get("vehicle_id"));if(!vehicles.isEmpty()){var car=vehicles.get(0);record.put("vehicle",car.get("make")+" "+car.get("model")+" / "+car.get("registration"));}}
   recent.add(record);
  }
  var result=new LinkedHashMap<String,Object>();result.put("days",days);result.put("from",from.toString());result.put("to",today.toString());result.put("timezone","Asia/Riyadh");result.put("generatedAt",Instant.now().toString());result.put("activeRentals",active);result.put("availableVehicles",fleet?available:null);result.put("totalVehicles",fleet?total:null);result.put("pickupsToday",pickups);result.put("needsAttention",attention.stream().mapToLong(row->((Number)row.get("value")).longValue()).sum());result.put("activity",activity);result.put("fleet",fleetStatus);result.put("attention",attention);result.put("recentBookings",recent);result.put("access",Map.of("fleet",fleet,"bookings",!kinds.isEmpty(),"maintenance",maintenance,"finance",finance));
  result.putAll(insights.build(today,days,permissions));
  return result;
 }
}
