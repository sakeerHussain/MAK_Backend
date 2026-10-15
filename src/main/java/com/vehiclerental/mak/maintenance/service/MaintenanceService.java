package com.vehiclerental.mak.maintenance.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.common.storage.StoredUploads;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.maintenance.dto.response.MaintenanceReceipt;
import com.vehiclerental.mak.maintenance.entity.MaintenanceExpense;
import com.vehiclerental.mak.maintenance.entity.MaintenanceJob;
import com.vehiclerental.mak.maintenance.repository.MaintenanceRepository;
import com.vehiclerental.mak.rentals.repository.RentalRepository;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Service
public class MaintenanceService {
 private static final Map<String,Set<String>> ITEMS=Map.of(
  "Maintenance Log",Set.of("Oil & Filter Change","Engine Air Filter","Fuel Filter","Wiper Blades","Battery Change","Tire Change","Tire Rotation","Wheel Alignment","Wheel Balancing","Brake Service","Transmission Oil","Differential Oil","AC Service","General Service","Others"),
  "Accident & Repair Log",Set.of("Body Repair","Engine Repair","Suspension","Electrical","Windshield"));
 private static final Set<String> STATUSES=Set.of("Open","In Progress","Completed","Cancelled");
 private static final Set<String> TASKS=Set.of("General service","Oil","Tyres","Brakes");
 private static final Set<String> CATEGORIES=Set.of("Spare Parts","Workshop Labor","Workshop Other","Other");
 private final MaintenanceRepository jobs;private final VehicleRepository vehicles;private final RentalRepository rentals;private final StoredUploads uploads;private final ObjectMapper json;
 public MaintenanceService(MaintenanceRepository jobs,VehicleRepository vehicles,RentalRepository rentals,StoredUploads uploads,ObjectMapper json){this.jobs=jobs;this.vehicles=vehicles;this.rentals=rentals;this.uploads=uploads;this.json=json;}
 @Transactional(readOnly=true) public List<Map<String,Object>> list(){return jobs.findAll().stream().sorted(Comparator.comparing(MaintenanceJob::getId).reversed()).map(this::response).toList();}
 @Transactional(readOnly=true) public Map<String,Object> get(long id){return response(require(id));}
 @Transactional public Map<String,Object> save(Long id,Map<String,Object> data,MultiValueMap<String,MultipartFile> files){
  MaintenanceJob job=id==null?new MaintenanceJob():jobs.lockById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Maintenance job not found."));
  if(id!=null && !active(job.getStatus()))throw conflict("Completed and cancelled jobs are locked.");
  long vehicleId=integer(data.get("vehicleId"),true);
  long oldVehicleId=id==null?vehicleId:job.getVehicleId();
  // Lock both cars in ID order when moving open work to a different car.
  Map<Long,Vehicle> locked=new HashMap<>();for(long vehicle:java.util.stream.LongStream.of(vehicleId,oldVehicleId).distinct().sorted().toArray())
   locked.put(vehicle,vehicles.lockById(vehicle).orElseThrow(()->bad("Select a vehicle from Fleet.")));
  Vehicle vehicle=locked.get(vehicleId);
  String status=text(data.get("status"),20),type=text(data.get("workType"),40),item=text(data.get("workItem"),80);
  if(!STATUSES.contains(status) || !ITEMS.containsKey(type) || !ITEMS.get(type).contains(item))throw bad("Choose a valid work type, work item and status.");
  if(id!=null && "In Progress".equals(job.getStatus()) && "Open".equals(status))throw conflict("An in-progress job cannot return to Open.");
  if(active(status) && ("In Use".equals(vehicle.getStatus()) || rentals.countByVehicleIdAndStatus(vehicleId,"Active")>0))throw conflict("Complete the active rental before opening maintenance.");
  LocalDate date=date(data.get("serviceDate"),true);Long reading=integer(data.get("odometerKm"),false);
  if("Completed".equals(status) && reading==null)throw bad("Odometer is required for completed work.");
  if("Completed".equals(status) && date.isAfter(LocalDate.now()))throw bad("Completed work cannot have a future service date.");
  List<String> completed=new ArrayList<>();Object taskData=data.get("completedTasks");
  if("Completed".equals(status)){
   if(!(taskData instanceof List<?> taskRows))throw bad("Choose valid completed tasks.");
   for(Object task:taskRows){String t=String.valueOf(task);if(!TASKS.contains(t)||completed.contains(t))throw bad("Choose valid completed tasks.");completed.add(t);}
  }
  LocalDate nextDate=type.equals("Maintenance Log")?date(data.get("nextServiceDate"),false):null;
  Long nextReading=type.equals("Maintenance Log")?integer(data.get("nextServiceOdometerKm"),false):null;
  if(nextDate!=null && nextDate.isBefore(date))throw bad("Next service date cannot be before the service date.");
  if(nextReading!=null && (reading==null || nextReading<=reading))throw bad("Next service odometer must exceed the job odometer.");
  List<Map<String,Object>> expenseRows=rows(data.getOrDefault("expenseLines",List.of()));if(expenseRows.size()>100)throw bad("Use at most 100 expense lines.");
  List<MaintenanceExpense> oldExpenses=new ArrayList<>(job.getExpenseLines());
  job.setVehicleId(vehicleId);job.setWorkType(type);job.setWorkItem(item);job.setStatus(status);job.setServiceDate(date);job.setOdometerKm(reading);
  job.setGarage(text(data.get("garage"),200));job.setNotes(text(data.get("notes"),10000));job.setNextServiceDate(nextDate);job.setNextServiceOdometerKm(nextReading);
  job.setCompletedTasks(json.writeValueAsString(completed));job.setUpdatedAt(Instant.now());if(id==null)job.setCreatedAt(Instant.now());job.setTotalAmount(BigDecimal.ZERO);
  if(id==null){jobs.saveAndFlush(job);job.setJobId("MNT-"+String.format("%04d",job.getId()));}
  List<MaintenanceExpense> expenses=new ArrayList<>();Set<String> keys=new HashSet<>(),keptReceipts=new HashSet<>();BigDecimal total=BigDecimal.ZERO;
  for(var row:expenseRows){
   String key=text(row.get("id"),80);if(!key.matches("[A-Za-z0-9-]{1,80}") || !keys.add(key))throw bad("Each expense line needs a unique ID.");
   String category=text(row.get("category"),30),description=text(row.get("description"),1000);if(!CATEGORIES.contains(category)||description.isBlank())throw bad("Complete expense category and description.");
   BigDecimal amount;try{amount=new BigDecimal(String.valueOf(row.get("amount")));}catch(Exception e){throw bad("Enter a valid expense amount.");}
   if(amount.signum()<=0 || amount.stripTrailingZeros().scale()>2 || amount.compareTo(new BigDecimal("9999999999.99"))>0)throw bad("Expense amounts must be positive SAR values with up to two decimals.");
   MaintenanceExpense expense=new MaintenanceExpense();expense.setClientKey(key);expense.setCategory(category);expense.setDescription(description);expense.setAmount(amount.setScale(2));
   MultipartFile file=files.getFirst("receipt-"+key);MaintenanceExpense old=oldExpenses.stream().filter(e->e.getClientKey().equals(key)).findFirst().orElse(null);
   if(file!=null){var stored=uploads.store("maintenance",job.getId(),file);expense.setReceiptKey(stored.key());expense.setReceiptName(stored.name());expense.setReceiptType(stored.type());expense.setReceiptSize(stored.size());}
   else if(row.get("receipt")!=null){
    if(!(row.get("receipt") instanceof Map<?,?> receipt) || old==null || old.getReceiptKey()==null || !old.getReceiptKey().equals(receipt.get("id")))throw bad("Invalid receipt reference.");
    expense.setReceiptKey(old.getReceiptKey());expense.setReceiptName(old.getReceiptName());expense.setReceiptType(old.getReceiptType());expense.setReceiptSize(old.getReceiptSize());
   }
   if(expense.getReceiptKey()!=null)keptReceipts.add(expense.getReceiptKey());expenses.add(expense);total=total.add(amount);
  }
  job.getExpenseLines().clear();job.getExpenseLines().addAll(expenses);job.setTotalAmount(total);jobs.saveAndFlush(job);
  for(var expense:oldExpenses)if(expense.getReceiptKey()!=null && !keptReceipts.contains(expense.getReceiptKey()))uploads.deleteAfterCommit("maintenance",job.getId(),expense.getReceiptKey());
  if("Completed".equals(status))resetCounters(vehicle,job,completed);
  reconcile(vehicle);if(oldVehicleId!=vehicleId)reconcile(locked.get(oldVehicleId));
  return response(job);
 }
 private void reconcile(Vehicle vehicle){
  if(jobs.activeCount(vehicle.getId())>0){if(vehicle.getMaintenanceReturnStatus()==null)vehicle.setMaintenanceReturnStatus(vehicle.getStatus());vehicle.setStatus("Under Maintenance");}
  else if(vehicle.getMaintenanceReturnStatus()!=null){vehicle.setStatus(rentals.countByVehicleIdAndStatus(vehicle.getId(),"Active")>0?"In Use":vehicle.getMaintenanceReturnStatus());vehicle.setMaintenanceReturnStatus(null);}
  vehicles.save(vehicle);
 }
 private void resetCounters(Vehicle vehicle,MaintenanceJob job,List<String> completed){
  long reading=job.getOdometerKm();vehicle.setCurrentOdometerKm(Math.max(value(vehicle.getCurrentOdometerKm()),reading));
  for(String task:completed){
   Long previous=switch(task){case "General service"->vehicle.getLastServiceKm();case "Oil"->vehicle.getLastOilKm();case "Tyres"->vehicle.getLastTyreKm();default->vehicle.getLastBrakeKm();};
   if(reading<value(previous))continue;
   Long interval=null;String matching=switch(task){case "General service"->"General Service";case "Oil"->"Oil & Filter Change";case "Tyres"->"Tire Change";default->"Brake Service";};
   if("Maintenance Log".equals(job.getWorkType()) && matching.equals(job.getWorkItem()) && job.getNextServiceOdometerKm()!=null)interval=job.getNextServiceOdometerKm()-reading;
   switch(task){case "General service"->{vehicle.setLastServiceKm(reading);if(interval!=null)vehicle.setServiceIntervalKm(interval);}case "Oil"->{vehicle.setLastOilKm(reading);if(interval!=null)vehicle.setOilIntervalKm(interval);}case "Tyres"->{vehicle.setLastTyreKm(reading);if(interval!=null)vehicle.setTyreIntervalKm(interval);}default->{vehicle.setLastBrakeKm(reading);if(interval!=null)vehicle.setBrakeIntervalKm(interval);}}
  }
 }
 @Transactional(readOnly=true) public MaintenanceReceipt receipt(long id,String key){var job=require(id);var expense=job.getExpenseLines().stream().filter(e->e.getClientKey().equals(key)&&e.getReceiptKey()!=null).findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Receipt not found."));return new MaintenanceReceipt(uploads.resource("maintenance",id,expense.getReceiptKey()),expense.getReceiptName(),expense.getReceiptType());}
 private Map<String,Object> response(MaintenanceJob j){
  Map<String,Object> out=new LinkedHashMap<>();out.put("id",j.getId());out.put("jobId",j.getJobId());out.put("vehicleId",j.getVehicleId());out.put("workType",j.getWorkType());out.put("workItem",j.getWorkItem());out.put("status",j.getStatus());out.put("serviceDate",j.getServiceDate().toString());out.put("odometerKm",j.getOdometerKm());out.put("garage",j.getGarage());out.put("notes",j.getNotes());out.put("nextServiceDate",j.getNextServiceDate()==null?"":j.getNextServiceDate().toString());out.put("nextServiceOdometerKm",j.getNextServiceOdometerKm());out.put("completedTasks",json.readValue(j.getCompletedTasks(),new TypeReference<List<String>>(){}));out.put("totalAmount",j.getTotalAmount());
  out.put("expenseLines",j.getExpenseLines().stream().map(e->{Map<String,Object> line=new LinkedHashMap<>();line.put("id",e.getClientKey());line.put("category",e.getCategory());line.put("amount",e.getAmount());line.put("description",e.getDescription());line.put("receipt",e.getReceiptKey()==null?null:Map.of("id",e.getReceiptKey(),"name",e.getReceiptName(),"size",e.getReceiptSize(),"url","/api/maintenance/"+j.getId()+"/expenses/"+e.getClientKey()+"/receipt"));return line;}).toList());return out;
 }
 private MaintenanceJob require(long id){return jobs.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Maintenance job not found."));}
 private String text(Object v,int max){String t=Objects.toString(v,"").trim();if(t.length()>max)throw bad("Field exceeds "+max+" characters.");return t;}
 private Long integer(Object v,boolean required){if(v==null || String.valueOf(v).isBlank()){if(required)throw bad("Select a vehicle.");return null;}try{long n=new BigDecimal(String.valueOf(v)).longValueExact();if(n<0 || n>999999999L || (required&&n==0))throw bad("Enter a non-negative whole kilometre reading or valid vehicle ID.");return n;}catch(ArithmeticException|NumberFormatException e){throw bad("Enter a whole number.");}}
 private LocalDate date(Object v,boolean required){String s=Objects.toString(v,"");if(s.isBlank()){if(required)throw bad("Service date is required.");return null;}try{return LocalDate.parse(s);}catch(Exception e){throw bad("Enter a valid date.");}}
 @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Object v){if(!(v instanceof List<?> rows)||rows.stream().anyMatch(row->!(row instanceof Map<?,?>)))throw bad("Invalid expense lines.");return (List<Map<String,Object>>)v;}
 private boolean active(String s){return "Open".equals(s)||"In Progress".equals(s);}
 private long value(Long n){return n==null?0:n;}
 private ApiException bad(String m){return new ApiException(HttpStatus.BAD_REQUEST,m);}private ApiException conflict(String m){return new ApiException(HttpStatus.CONFLICT,m);}

}
