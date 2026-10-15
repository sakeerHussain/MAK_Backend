package com.vehiclerental.mak.auth.security;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthRateLimitFilter extends OncePerRequestFilter {
 private record Window(long start,int count){}
 private final ConcurrentHashMap<String,Window> requests=new ConcurrentHashMap<>();private final int limit;
 public AuthRateLimitFilter(int limit){this.limit=limit;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  if(request.getMethod().equals("POST") && request.getRequestURI().startsWith(request.getContextPath()+"/api/auth/") && !request.getRequestURI().endsWith("/logout")){
   long now=Instant.now().getEpochSecond();if(requests.size()>10000)requests.entrySet().removeIf(entry->now-entry.getValue().start()>900);
   if(requests.size()>10000 && !requests.containsKey(request.getRemoteAddr())){reject(response);return;}
   var window=requests.compute(request.getRemoteAddr(),(key,old)->old==null || now-old.start()>900?new Window(now,1):new Window(old.start(),old.count()+1));
   if(window.count()>limit){reject(response);return;}
  }
  chain.doFilter(request,response);
 }
 private void reject(HttpServletResponse response)throws IOException{response.setStatus(429);response.setContentType("application/problem+json");response.setHeader("Retry-After","900");response.getWriter().write("{\"detail\":\"Too many authentication attempts. Try again in 15 minutes.\"}");}
}
