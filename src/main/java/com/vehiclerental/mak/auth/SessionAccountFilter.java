package com.vehiclerental.mak.auth;
import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
public class SessionAccountFilter extends OncePerRequestFilter {
 private final AccountService accounts;
 public SessionAccountFilter(AccountService accounts){this.accounts=accounts;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  var authentication=SecurityContextHolder.getContext().getAuthentication();
  if(authentication!=null && authentication.getPrincipal() instanceof AccountPrincipal principal && accounts.active(principal)==null){
   var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();
  }
  chain.doFilter(request,response);
 }
}
