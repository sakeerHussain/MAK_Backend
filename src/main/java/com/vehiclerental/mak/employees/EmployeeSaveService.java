package com.vehiclerental.mak.employees;
import java.time.LocalDate;
import java.util.*;
import com.vehiclerental.mak.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EmployeeSaveService {
    private final EmployeeService employees;
    private final EmployeeFileService files;
    public EmployeeSaveService(EmployeeService employees,EmployeeFileService files) { this.employees=employees;this.files=files; }
    @Transactional
    public EmployeeResponse save(Long id,EmployeeRequest request,Map<String,Object> attachments,MultiValueMap<String,MultipartFile> uploads) {
        if(id!=null)employees.requireLocked(id);
        EmployeeResponse previous=id==null?null:employees.get(id);
        EmployeeResponse saved=id==null?employees.create(request):employees.update(id,request);
        long employeeId=saved.id();
        Object photo=attachments.get("photo");
        if (uploads.getFirst("photo")!=null) files.uploadPhoto(employeeId,uploads.getFirst("photo"));
        else if(photo==null && previous!=null && previous.photo()!=null) files.delete(employeeId,previous.photo().id());
        else if(photo!=null && (previous==null || previous.photo()==null || !String.valueOf(previous.photo().id()).equals(String.valueOf(photo)))) throw bad("Invalid employee photo reference.");
        Object documentRows=attachments.get("documents");
        if (!(documentRows instanceof List<?> rows) || rows.size()>8) throw bad("Provide at most eight employee documents.");
        Set<Long> keep=new HashSet<>(); Set<String> types=new HashSet<>();
        for(Object value:rows) {
            if (!(value instanceof Map<?,?> row)) throw bad("Invalid employee document.");
            String type=String.valueOf(row.get("type"));if(!types.add(type))throw bad("Duplicate employee document type.");
            MultipartFile upload=uploads.getFirst(String.valueOf(row.get("uploadKey")));
            if(upload!=null) { String date=Objects.toString(row.get("expiryDate"),"");try { files.uploadDocument(employeeId,type,date.isBlank()?null:LocalDate.parse(date),upload); } catch(java.time.format.DateTimeParseException e) { throw bad("Enter a valid document expiry date."); } }
            else {
                long fileId;try{fileId=Long.parseLong(String.valueOf(row.get("id")));}catch(Exception e){throw bad("Choose a document file.");}
                if(previous==null || previous.documents().stream().noneMatch(file->file.id()==fileId && type.equals(file.type())))throw bad("Invalid employee document reference.");
                keep.add(fileId);
            }
        }
        if(previous!=null) for(var file:previous.documents())
            if(!keep.contains(file.id()) && !types.contains(file.type()))files.delete(employeeId,file.id());
        return employees.get(employeeId);
    }
    private ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
}
