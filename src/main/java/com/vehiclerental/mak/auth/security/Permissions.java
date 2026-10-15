package com.vehiclerental.mak.auth.security;

import java.util.*;

public final class Permissions {
 private Permissions(){}
 public static final List<String> MODULES=List.of("fleet","drivers","employees","vendors","customers","bookings","corporate-trips","maintenance","inspections","invoices");
 public static final Map<String,Set<String>> ROLES;
 static {
  var admin=new TreeSet<String>();admin.add("dashboard.read");MODULES.forEach(m->{admin.add(m+".read");admin.add(m+".write");});admin.addAll(Set.of("users.manage","audit.read","settings.read"));
  var manager=new TreeSet<String>(admin);manager.removeAll(Set.of("users.manage","employees.write","audit.read","settings.read"));
  var operations=new TreeSet<String>();operations.add("dashboard.read");for(String m:List.of("fleet","drivers","vendors","customers","bookings","corporate-trips","maintenance","inspections")){operations.add(m+".read");operations.add(m+".write");}
  var finance=new TreeSet<String>(Set.of("dashboard.read","fleet.read","drivers.read","customers.read","bookings.read","corporate-trips.read","invoices.read","invoices.write"));
  var viewer=new TreeSet<String>(Set.of("dashboard.read","fleet.read","drivers.read","customers.read","bookings.read","corporate-trips.read","maintenance.read","inspections.read"));
  ROLES=Collections.unmodifiableMap(new LinkedHashMap<>(Map.of("Admin",Set.copyOf(admin),"Manager",Set.copyOf(manager),"Operations",Set.copyOf(operations),"Finance",Set.copyOf(finance),"Viewer",Set.copyOf(viewer))));
 }
 public static Set<String> forRole(String role){return ROLES.getOrDefault(role,Set.of());}
}
