package com.vehiclerental.mak.notifications.booking.service;

import com.vehiclerental.mak.auth.security.RolePolicy;
import com.vehiclerental.mak.auth.service.RoleService;
import java.util.Set;

/** One recipient policy for in-app alerts, escalation queueing and SMTP delivery. */
public final class BookingNotificationAudience {
 private BookingNotificationAudience() {}
 public static boolean eligible(RoleService roles,String kind,String builtInRole,Long customRoleId,boolean supervisors) {
  String module=switch(kind){case "Individual" -> "bookings";case "Corporate" -> "corporate-trips";default -> null;};
  if(module==null || (supervisors&&!Set.of("Admin","Manager").contains(builtInRole)))return false;
  var permissions=roles.resolve(builtInRole,customRoleId);
  return permissions.contains(module+".read") && RolePolicy.operates(permissions,module);
 }
}
