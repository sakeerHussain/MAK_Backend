package com.vehiclerental.mak.notifications.email.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Email-safe presentation; all dynamic content is escaped before HTML insertion. */
public final class InvitationEmailTemplate {
 private InvitationEmailTemplate() {}
 private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("d MMMM uuuu 'at' h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Riyadh"));
 public static String expiry(Instant at) { return EXPIRY.format(at)+" KSA (UTC+3)"; }
 public static String plain(String name,String email,String role,String url,Instant expires) {
  return "You're invited to MAK\n\nHello "+name+",\n\nYou've been invited to join the MAK administration portal. Create your account to get started.\n\nWork email: "+email+"\nAssigned role: "+role+"\n\nAccept invitation: "+url+"\n\nExpires "+expiry(expires)+".\nUse the email address above to create your account.\n\nThis invitation is personal. Please do not forward it. If you weren't expecting it, you can ignore this email.\n\nMAK International";
 }
 public static String html(String name,String email,String role,String url,Instant expires) {
  if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IllegalArgumentException("Invitation URL must use HTTP or HTTPS");
  return """
<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>You're invited to MAK</title></head>
<body style="margin:0;padding:0;background:#f3f6f8;color:#102a43;font-family:Arial,Helvetica,sans-serif;">
<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">Your invitation to the MAK administration portal.</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:28px 12px;">
<table role="presentation" width="600" cellpadding="0" cellspacing="0" style="width:100%;max-width:600px;background:#ffffff;border-top:5px solid #0086bd;"><tr><td style="padding:32px 28px;">
<img src="cid:mak-logo" width="255" alt="MAK International" style="display:block;width:255px;max-width:100%;height:auto;border:0;">
<h1 style="font-size:30px;line-height:1.2;letter-spacing:-0.6px;margin:40px 0 28px;color:#102a43;">You're invited to MAK</h1>
<p style="font-size:20px;line-height:1.4;margin:0 0 18px;">Hello {{name}},</p>
<p style="font-size:16px;line-height:1.6;color:#52606d;margin:0 0 28px;">You've been invited to join the MAK administration portal. Create your account to get started.</p>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="font-size:15px;line-height:1.5;table-layout:fixed;">
<tr><td width="32%" style="padding:12px 8px 12px 0;color:#60758c;border-bottom:1px solid #dce5ed;">Work email</td><td style="padding:12px 0;border-bottom:1px solid #dce5ed;overflow-wrap:anywhere;word-break:break-word;">{{email}}</td></tr>
<tr><td style="padding:12px 8px 12px 0;color:#60758c;border-bottom:1px solid #dce5ed;">Assigned role</td><td style="padding:12px 0;border-bottom:1px solid #dce5ed;overflow-wrap:anywhere;">{{role}}</td></tr></table>
<table role="presentation" cellpadding="0" cellspacing="0" style="margin:28px 0 20px;"><tr><td bgcolor="#0086bd" style="border-radius:4px;mso-padding-alt:16px 28px;"><a href="{{url}}" style="display:inline-block;padding:16px 28px;color:#ffffff;background:#0086bd;border-radius:4px;text-decoration:none;font-size:16px;line-height:20px;font-weight:bold;">Accept invitation</a></td></tr></table>
<p style="font-size:14px;line-height:1.6;margin:0 0 6px;">Expires {{expiry}}.</p>
<p style="font-size:14px;line-height:1.6;margin:0;color:#60758c;">Use the email address above to create your account.</p>
<div style="border-top:1px solid #dce5ed;margin-top:30px;padding-top:24px;">
<p style="font-size:13px;line-height:1.6;margin:0 0 8px;color:#60758c;">Button not working? Copy and paste this link into your browser:</p>
<p style="font-size:12px;line-height:1.6;margin:0 0 20px;word-break:break-all;overflow-wrap:anywhere;"><a href="{{url}}" style="color:#007bb7;">{{url}}</a></p>
<p style="font-size:13px;line-height:1.6;margin:0 0 20px;color:#60758c;">This invitation is personal. Please do not forward it. If you weren't expecting it, you can ignore this email.</p>
<p style="font-size:14px;line-height:1.5;margin:0;color:#60758c;">MAK International</p></div>
</td></tr></table></td></tr></table></body></html>
""".replace("{{name}}",escape(name)).replace("{{email}}",escape(email)).replace("{{role}}",escape(role)).replace("{{expiry}}",escape(expiry(expires))).replace("{{url}}",escape(url));
 }
 private static String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
