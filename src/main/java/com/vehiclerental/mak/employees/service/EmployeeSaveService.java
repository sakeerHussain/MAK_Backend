package com.vehiclerental.mak.employees.service;

import com.vehiclerental.mak.auth.service.AccountService;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.dto.request.EmployeeRequest;
import com.vehiclerental.mak.employees.dto.response.EmployeeResponse;

import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EmployeeSaveService {
    private final EmployeeService employees;
    private final EmployeeFileService files;
    private final AccountService accounts;

    public EmployeeSaveService(EmployeeService employees, EmployeeFileService files, AccountService accounts) {
        this.employees=employees; this.files=files; this.accounts=accounts;
    }

    @Transactional
    public EmployeeResponse save(Long id, EmployeeRequest request, Map<String,Object> attachments, MultiValueMap<String,MultipartFile> uploads) {
        if (id!=null) employees.requireLocked(id);
        EmployeeResponse previous=id==null?null:employees.get(id);
        AttachmentPlan plan=plan(previous,attachments,uploads);
        // Explicit removals have the same authorization as the direct file-delete endpoint.
        // Same-type replacements remain edits, consistent with the upload endpoints.
        if (!plan.removedIds().isEmpty()) accounts.requirePermission("employees.archive");

        EmployeeResponse saved=id==null?employees.create(request):employees.update(id,request);
        long employeeId=saved.id();
        if (plan.photo()!=null) files.uploadPhoto(employeeId,plan.photo());
        for (DocumentPlan document:plan.documents()) {
            if (document.upload()!=null) files.uploadDocument(employeeId,document.type(),document.expiryDate(),document.upload());
        }
        for (long fileId:plan.removedIds()) files.delete(employeeId,fileId);
        return employees.get(employeeId);
    }

    private AttachmentPlan plan(EmployeeResponse previous, Map<String,Object> attachments, MultiValueMap<String,MultipartFile> uploads) {
        Set<Long> removed=new LinkedHashSet<>();
        MultipartFile photoUpload=uploads.getFirst("photo");
        Object photo=attachments.get("photo");
        if (photoUpload==null) {
            if (photo==null && previous!=null && previous.photo()!=null) removed.add(previous.photo().id());
            else if (photo!=null && (previous==null || previous.photo()==null || !String.valueOf(previous.photo().id()).equals(String.valueOf(photo))))
                throw bad("Invalid employee photo reference.");
        }

        Object documentRows=attachments.get("documents");
        if (!(documentRows instanceof List<?> rows) || rows.size()>8) throw bad("Provide at most eight employee documents.");
        Set<Long> keep=new HashSet<>();
        Set<String> types=new HashSet<>(), replacements=new HashSet<>();
        List<DocumentPlan> documents=new ArrayList<>();
        for (Object value:rows) {
            if (!(value instanceof Map<?,?> row)) throw bad("Invalid employee document.");
            String type=String.valueOf(row.get("type"));
            if (!types.add(type)) throw bad("Duplicate employee document type.");
            MultipartFile upload=uploads.getFirst(String.valueOf(row.get("uploadKey")));
            if (upload!=null) {
                String date=Objects.toString(row.get("expiryDate"),"");
                LocalDate expiry;
                try { expiry=date.isBlank()?null:LocalDate.parse(date); }
                catch (java.time.format.DateTimeParseException e) { throw bad("Enter a valid document expiry date."); }
                replacements.add(type);
                documents.add(new DocumentPlan(type,expiry,upload));
            } else {
                long fileId;
                try { fileId=Long.parseLong(String.valueOf(row.get("id"))); }
                catch (Exception e) { throw bad("Choose a document file."); }
                if (previous==null || previous.documents().stream().noneMatch(file->file.id()==fileId && type.equals(file.type())))
                    throw bad("Invalid employee document reference.");
                keep.add(fileId);
            }
        }
        if (previous!=null) for (var file:previous.documents()) {
            if (!keep.contains(file.id()) && !replacements.contains(file.type())) removed.add(file.id());
        }
        return new AttachmentPlan(photoUpload,List.copyOf(documents),Set.copyOf(removed));
    }

    private record DocumentPlan(String type, LocalDate expiryDate, MultipartFile upload) { }
    private record AttachmentPlan(MultipartFile photo, List<DocumentPlan> documents, Set<Long> removedIds) { }
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,message); }
}
