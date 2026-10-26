package com.vehiclerental.mak.auth.security;
import java.util.*;
public final class RolePolicy {
 private RolePolicy(){}
 public static final List<String> ACTIONS=List.of("read","create","edit","archive");
 public static final Set<String> SENSITIVE=Set.of("invoices.issue","invoices.credit","payments.refund","billing-profile.write");
 public static final Set<String> REMOVAL_MODULES=Set.of("fleet","drivers","employees","vendors","invoices");
 public static List<String> actions(String module){return REMOVAL_MODULES.contains(module)?ACTIONS:List.of("read","create","edit");}
 public static Set<String> catalog(){var result=new TreeSet<String>(Set.of("dashboard.read","audit.read","settings.read","users.manage"));for(String m:Permissions.MODULES)for(String a:actions(m))result.add(m+"."+a);result.addAll(SENSITIVE);return result;}
 public static Set<String> expand(Set<String> original){var result=new TreeSet<String>(original);for(String m:Permissions.MODULES)if(original.contains(m+".write"))for(String a:actions(m))result.add(m+"."+a);if(original.contains("invoices.write"))result.addAll(SENSITIVE);if(original.contains("bookings.write"))result.add("payments.refund");return Set.copyOf(result);}
 public static boolean operates(Set<String> permissions,String module){return permissions.contains(module+".write")||List.of("create","edit","archive").stream().anyMatch(a->permissions.contains(module+"."+a));}
}
