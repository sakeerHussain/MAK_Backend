package com.vehiclerental.mak.notifications.email.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Optional;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.io.ByteArrayOutputStream;
import com.vehiclerental.mak.notifications.email.entity.EmailMessage;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

class InvitationEmailTemplateTests {
 private final Instant expiry=Instant.parse("2026-11-06T07:00:00Z");
 @Test void usesExactKsaExpiryAndEscapesDynamicContent() {
  String html=InvitationEmailTemplate.html("<script>alert(1)</script>","a@example.com","<Admin & Ops>","https://example.com/#/sign-up?invitation=test&x=1",expiry);
  assertFalse(html.contains("<script>"));assertTrue(html.contains("&lt;Admin &amp; Ops&gt;"));
  assertTrue(html.contains("6 November 2026 at 10:00 AM KSA (UTC+3)"));
  assertTrue(html.contains("invitation=test&amp;x=1"));assertTrue(html.contains("cid:mak-logo"));
  assertThrows(IllegalArgumentException.class,()->InvitationEmailTemplate.html("A","a@example.com","Viewer","javascript:alert(1)",expiry));
 }
 @Test void plainVersionIncludesRealLinkRoleEmailAndExpiry() {
  String body=InvitationEmailTemplate.plain("Ahmed","a@example.com","Operations","https://example.com/invite",expiry);
  for(String part:new String[]{"Ahmed","a@example.com","Operations","https://example.com/invite","10:00 AM KSA"})assertTrue(body.contains(part));
 }
 @Test void deliveryContainsAlternativeBodiesAndInlineLogoWithoutSendingRealMail() throws Exception {
  EmailRepository repository=mock(EmailRepository.class);JavaMailSender sender=mock(JavaMailSender.class);
  @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);
  when(provider.getIfAvailable()).thenReturn(sender);
  MimeMessage message=new MimeMessage(Session.getInstance(new Properties()));when(sender.createMimeMessage()).thenReturn(message);
  EmailMessage email=new EmailMessage();email.setRecordKind("Auth");email.setRecipient("test@example.invalid");email.setSubject("Invitation");email.setStatus("Queued");email.setNextAttemptAt(Instant.now().minusSeconds(1));
  email.setBody(InvitationEmailTemplate.plain("Ahmed","test@example.invalid","Viewer","https://example.com/invite",expiry));
  email.setHtmlBody(InvitationEmailTemplate.html("Ahmed","test@example.invalid","Viewer","https://example.com/invite",expiry));
  when(repository.lockById(1L)).thenReturn(Optional.of(email));
  new EmailDelivery(repository,provider,null,null,"noreply@example.invalid").deliver(1);
  assertEquals("Sent",email.getStatus());verify(sender).send(message);
  message.saveChanges();ByteArrayOutputStream output=new ByteArrayOutputStream();message.writeTo(output);
  String mime=output.toString(java.nio.charset.StandardCharsets.UTF_8);
  for(String type:new String[]{"multipart/alternative","text/plain","text/html","image/jpeg","Content-ID: <mak-logo>"})assertTrue(mime.contains(type),type);
 }
 @Test void existingPlainEmailsStillSendWithoutMultipartLogo() throws Exception {
  EmailRepository repository=mock(EmailRepository.class);JavaMailSender sender=mock(JavaMailSender.class);
  @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);
  when(provider.getIfAvailable()).thenReturn(sender);MimeMessage message=new MimeMessage(Session.getInstance(new Properties()));when(sender.createMimeMessage()).thenReturn(message);
  EmailMessage email=new EmailMessage();email.setRecordKind("Auth");email.setRecipient("test@example.invalid");email.setSubject("Reset");email.setBody("Reset link");email.setStatus("Queued");email.setNextAttemptAt(Instant.now().minusSeconds(1));when(repository.lockById(1L)).thenReturn(Optional.of(email));
  new EmailDelivery(repository,provider,null,null,"noreply@example.invalid").deliver(1);
  assertEquals("Sent",email.getStatus());assertEquals("Reset link",message.getContent());
 }
}
