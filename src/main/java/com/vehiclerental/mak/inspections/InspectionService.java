package com.vehiclerental.mak.inspections;

import java.time.Instant;
import java.util.*;
import com.vehiclerental.mak.common.*;
import com.vehiclerental.mak.fleet.VehicleRepository;
import com.vehiclerental.mak.rentals.RentalRepository;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InspectionService {
    private final InspectionRepository inspections;
    private final VehicleRepository vehicles;
    private final RentalRepository rentals;
    private final StoredUploads uploads;
    public InspectionService(InspectionRepository inspections,VehicleRepository vehicles,RentalRepository rentals,StoredUploads uploads){
        this.inspections=inspections;this.vehicles=vehicles;this.rentals=rentals;this.uploads=uploads;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(){return inspections.findAllByOrderByInspectionDateDescIdDesc().stream().map(this::response).toList();}
    @Transactional(readOnly=true)
    public Map<String,Object> get(long id){return response(require(id));}
    @Transactional
    public Map<String,Object> save(Long id,InspectionRequest request,MultiValueMap<String,MultipartFile> files){
        Inspection inspection=id==null?new Inspection():inspections.lockById(id).orElseThrow(()->missing("Inspection not found."));
        if(!Set.of(0,25,50,75,100).contains(request.fuelLevel().intValueExact()))throw bad("Choose a valid fuel level.");
        if(request.bookingId()!=null){
            var rental=rentals.lockById(request.bookingId()).orElseThrow(()->bad("Linked booking not found."));
            if(!Objects.equals(rental.getVehicleId(),request.vehicleId()))throw bad("Choose a booking assigned to this car.");
        }
        var vehicle=vehicles.lockById(request.vehicleId()).orElseThrow(()->bad("Select a car from Fleet."));
        var previous=new HashMap<String,InspectionPhoto>();
        inspection.getPhotos().forEach(photo->previous.put(photo.getStorageKey(),photo));
        var keys=new HashSet<String>();var retained=new HashSet<String>();
        for(var photo:request.photos()){
            if(photo.uploadKey()!=null){
                if(photo.id()!=null || !keys.add(photo.uploadKey()) || files.getOrDefault(photo.uploadKey(),List.of()).size()!=1)throw bad("Choose one upload for each new photo.");
            }else if(photo.id()==null || !previous.containsKey(photo.id()) || !retained.add(photo.id()))throw bad("An attached photo does not belong to this inspection.");
        }
        if(!files.keySet().stream().filter(key->!key.equals("record")).collect(java.util.stream.Collectors.toSet()).equals(keys))throw bad("Unexpected or missing photo upload.");
        Instant now=Instant.now();
        if(id==null)inspection.setCreatedAt(now);
        inspection.setUpdatedAt(now);inspection.setVehicleId(request.vehicleId());inspection.setType(request.type());inspection.setCondition(request.condition());
        inspection.setInspectionDate(request.inspectionDate());inspection.setInspector(request.inspector().trim());inspection.setOdometerKm(request.odometerKm().longValueExact());
        inspection.setFuelLevel(request.fuelLevel().intValueExact());inspection.setBookingId(request.bookingId());inspection.setNotes(request.notes()==null?"":request.notes().trim());
        if(id==null){inspections.saveAndFlush(inspection);inspection.setInspectionId("INS-%04d".formatted(inspection.getId()));}
        var next=new ArrayList<InspectionPhoto>();
        for(var photo:request.photos()){
            if(photo.uploadKey()==null){next.add(previous.get(photo.id()));continue;}
            var stored=uploads.storeImage("inspections",inspection.getId(),files.getFirst(photo.uploadKey()));
            var row=new InspectionPhoto();row.setStorageKey(stored.key());row.setName(stored.name());row.setContentType(stored.type());row.setFileSize(stored.size());next.add(row);
        }
        previous.forEach((key,photo)->{if(!retained.contains(key))uploads.deleteAfterCommit("inspections",inspection.getId(),key);});
        inspection.getPhotos().clear();inspection.getPhotos().addAll(next);
        vehicle.setCurrentOdometerKm(Math.max(vehicle.getCurrentOdometerKm()==null?0:vehicle.getCurrentOdometerKm(),request.odometerKm().longValueExact()));
        inspections.saveAndFlush(inspection);return response(inspection);
    }
    @Transactional(readOnly=true)
    public PhotoFile photo(long id,String key){
        var photo=require(id).getPhotos().stream().filter(row->row.getStorageKey().equals(key)).findFirst().orElseThrow(()->missing("Photo not found."));
        return new PhotoFile(photo.getName(),photo.getContentType(),uploads.resource("inspections",id,key));
    }
    private Inspection require(long id){return inspections.findById(id).orElseThrow(()->missing("Inspection not found."));}
    private Map<String,Object> response(Inspection i){
        var out=new LinkedHashMap<String,Object>();
        out.put("id",i.getId());out.put("inspectionId",i.getInspectionId());out.put("vehicleId",i.getVehicleId());out.put("type",i.getType());out.put("condition",i.getCondition());
        out.put("inspectionDate",i.getInspectionDate());out.put("inspector",i.getInspector());out.put("odometerKm",i.getOdometerKm());out.put("fuelLevel",i.getFuelLevel());out.put("bookingId",i.getBookingId());out.put("notes",i.getNotes());
        out.put("createdAt",i.getCreatedAt());out.put("updatedAt",i.getUpdatedAt());
        out.put("photos",i.getPhotos().stream().map(photo->Map.<String,Object>of("id",photo.getStorageKey(),"name",photo.getName(),"type",photo.getContentType(),"size",photo.getFileSize(),"url","/api/inspections/"+i.getId()+"/photos/"+photo.getStorageKey())).toList());return out;
    }
    private ApiException bad(String text){return new ApiException(HttpStatus.BAD_REQUEST,text);}
    private ApiException missing(String text){return new ApiException(HttpStatus.NOT_FOUND,text);}
    public record PhotoFile(String name,String type,Resource resource){}
}
