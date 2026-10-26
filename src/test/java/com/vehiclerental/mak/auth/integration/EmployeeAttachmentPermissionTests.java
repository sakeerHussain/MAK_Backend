package com.vehiclerental.mak.auth.integration;

import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.service.AccountService;
import com.vehiclerental.mak.auth.service.RoleService;
import com.vehiclerental.mak.employees.dto.request.EmployeeRequest;
import com.vehiclerental.mak.employees.dto.response.EmployeeResponse;
import com.vehiclerental.mak.employees.repository.EmployeeFileRepository;
import com.vehiclerental.mak.employees.service.EmployeeFileService;
import com.vehiclerental.mak.employees.service.EmployeeService;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest @Transactional
class EmployeeAttachmentPermissionTests {
    @Autowired AccountRepository accounts;
    @Autowired AccountService accountService;
    @Autowired RoleService roles;
    @Autowired EmployeeService employees;
    @Autowired EmployeeFileService files;
    @Autowired EmployeeFileRepository records;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Account admin;

    @BeforeEach void setup() {
        admin=account("Admin",null);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    private Account account(String role,Long customRole) {
        var account=new Account();account.setName("Attachment test account");account.setEmail("attachment-"+UUID.randomUUID()+"@example.invalid");account.setPasswordHash("not-a-login-password");account.setRole(role);account.setCustomRoleId(customRole);account.setStatus("Active");account.setCreatedAt(Instant.now());return accounts.saveAndFlush(account);
    }
    private MockHttpSession session(String... grants) {
        var definition=roles.save(null,new LinkedHashMap<>(Map.of("name","Attachment role "+UUID.randomUUID(),"permissions",List.of(grants))),admin.getId());
        var account=account("Viewer",((Number)definition.get("id")).longValue());
        var session=new MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(account.getId(),account.getSessionVersion()),null,List.of())));return session;
    }
    private Map<String,Object> details() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        return new LinkedHashMap<>(Map.of("employeeId","ATT-"+suffix,"name","Original Employee","phone","+96650"+Integer.toUnsignedString(suffix.hashCode()),"jobTitle","Coordinator","department","Fleet","category","Technical","employmentType","Full-time","status","Active","monthlySalary",6500));
    }
    private MockMultipartFile image(String field,String name) {
        return new MockMultipartFile(field,name,"image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,1,2});
    }
    private Fixture fixture() {
        var data=details();var employee=employees.create(json.convertValue(data,EmployeeRequest.class));
        files.uploadPhoto(employee.id(),image("photo","original-photo.png"));
        files.uploadDocument(employee.id(),"Contract copy",null,image("doc","original-contract.png"));
        return new Fixture(data,employees.get(employee.id()));
    }
    private List<Map<String,Object>> documents(EmployeeResponse employee) {
        return employee.documents().stream().map(file->Map.<String,Object>of("id",file.id(),"type",file.type())).toList();
    }
    private Map<String,Object> attachments(EmployeeResponse employee,boolean keepPhoto,List<Map<String,Object>> documents) {
        var result=new LinkedHashMap<String,Object>();result.put("photo",keepPhoto?employee.photo().id():null);result.put("documents",documents);return result;
    }
    private int save(Fixture fixture,MockHttpSession session,Map<String,Object> attachments,MockMultipartFile... uploads)throws Exception {
        var data=new LinkedHashMap<>(fixture.data());data.put("name","Edited Employee");
        var request=multipart("/api/employees/"+fixture.employee().id()+"/save")
            .file(new MockMultipartFile("record","","application/json",json.writeValueAsBytes(data)))
            .file(new MockMultipartFile("attachments","","application/json",json.writeValueAsBytes(attachments)));
        for(var upload:uploads)request.file(upload);
        return mvc.perform(request.session(session).with(csrf().asHeader()).with(r->{r.setMethod("PUT");return r;})).andReturn().getResponse().getStatus();
    }
    private void intact(Fixture fixture)throws Exception {
        var employee=employees.get(fixture.employee().id());
        assertEquals("Original Employee",employee.details().name());
        assertEquals(fixture.employee().photo().id(),employee.photo().id());
        assertEquals(fixture.employee().documents().getFirst().id(),employee.documents().getFirst().id());
        assertTrue(files.download(employee.id(),employee.photo().id()).resource().exists());
        assertTrue(files.download(employee.id(),employee.documents().getFirst().id()).resource().exists());
    }
    @Test void editCannotRemoveDocumentThroughSaveOrDirectDelete()throws Exception {
        var fixture=fixture();var editor=session("employees.read","employees.edit");
        assertEquals(403,save(fixture,editor,attachments(fixture.employee(),true,List.of())));
        intact(fixture);
        assertEquals(403,mvc.perform(delete("/api/employees/"+fixture.employee().id()+"/files/"+fixture.employee().documents().getFirst().id()).session(editor).with(csrf().asHeader())).andReturn().getResponse().getStatus());
        intact(fixture);
    }
    @Test void editCannotClearPersistedPhoto()throws Exception {
        var fixture=fixture();
        assertEquals(403,save(fixture,session("employees.read","employees.edit"),attachments(fixture.employee(),false,documents(fixture.employee()))));
        intact(fixture);
    }
    @Test void editCanSaveDetailsWhileRetainingAttachments()throws Exception {
        var fixture=fixture();
        assertEquals(200,save(fixture,session("employees.read","employees.edit"),attachments(fixture.employee(),true,documents(fixture.employee()))));
        var saved=employees.get(fixture.employee().id());assertEquals("Edited Employee",saved.details().name());
        assertEquals(fixture.employee().photo().id(),saved.photo().id());assertEquals(fixture.employee().documents().getFirst().id(),saved.documents().getFirst().id());
    }
    @Test void editCanReplacePhotoAndDocumentWithoutRemoveGrant()throws Exception {
        var fixture=fixture();var rows=List.<Map<String,Object>>of(Map.of("type","Contract copy","uploadKey","replacement"));
        assertEquals(200,save(fixture,session("employees.read","employees.edit"),attachments(fixture.employee(),false,rows),image("photo","new-photo.png"),image("replacement","new-contract.png")));
        var saved=employees.get(fixture.employee().id());assertNotEquals(fixture.employee().photo().id(),saved.photo().id());assertNotEquals(fixture.employee().documents().getFirst().id(),saved.documents().getFirst().id());
        assertEquals("new-photo.png",saved.photo().name());assertEquals("new-contract.png",saved.documents().getFirst().name());
    }
    @Test void replacementCannotHideAnUnrelatedRemoval()throws Exception {
        var fixture=fixture();var extra=files.uploadDocument(fixture.employee().id(),"Passport copy",null,image("passport","passport.png"));
        var rows=List.<Map<String,Object>>of(Map.of("type","Contract copy","uploadKey","replacement"));
        assertEquals(403,save(fixture,session("employees.read","employees.edit"),attachments(fixture.employee(),true,rows),image("replacement","new-contract.png")));
        assertTrue(records.existsById(extra.id()));assertEquals("Original Employee",employees.get(fixture.employee().id()).details().name());assertTrue(records.existsById(fixture.employee().documents().getFirst().id()));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void editAndRemoveCanDeleteAttachmentsAndCommittedDiskFiles()throws Exception {
        var fixture=fixture();var employee=fixture.employee();
        var photoPath=files.download(employee.id(),employee.photo().id()).resource().getFile().toPath();
        var documentPath=files.download(employee.id(),employee.documents().getFirst().id()).resource().getFile().toPath();
        assertEquals(200,save(fixture,session("employees.read","employees.edit","employees.archive"),attachments(employee,false,List.of())));
        var saved=employees.get(employee.id());assertNull(saved.photo());assertTrue(saved.documents().isEmpty());
        assertFalse(Files.exists(photoPath));assertFalse(Files.exists(documentPath));assertEquals("Edited Employee",saved.details().name());
    }
    @Test void removeDoesNotImplyEditButStillAllowsDirectFileDeletion()throws Exception {
        var fixture=fixture();var remover=session("employees.read","employees.archive");
        assertEquals(403,save(fixture,remover,attachments(fixture.employee(),false,List.of())));intact(fixture);
        assertEquals(204,mvc.perform(delete("/api/employees/"+fixture.employee().id()+"/files/"+fixture.employee().documents().getFirst().id()).session(remover).with(csrf().asHeader())).andReturn().getResponse().getStatus());
    }
    @Test void createOnlyCanAddNewEmployeeWithAttachments()throws Exception {
        var data=details();var attachments=Map.of("documents",List.of(Map.of("type","Contract copy","uploadKey","doc")));
        var response=mvc.perform(multipart("/api/employees/save")
            .file(new MockMultipartFile("record","","application/json",json.writeValueAsBytes(data)))
            .file(new MockMultipartFile("attachments","","application/json",json.writeValueAsBytes(attachments)))
            .file(image("photo","new-photo.png")).file(image("doc","new-contract.png"))
            .session(session("employees.read","employees.create")).with(csrf().asHeader())).andReturn().getResponse();
        assertEquals(200,response.getStatus());var saved=json.readTree(response.getContentAsString());assertFalse(saved.path("photo").isNull());assertEquals(1,saved.path("documents").size());
    }
    private record Fixture(Map<String,Object> data,EmployeeResponse employee) { }
}
