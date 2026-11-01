package com.vehiclerental.mak.enquiries;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;
/** Per-instance abuse limit; uses the socket peer, never an untrusted forwarded header. */
public class PublicEnquiryRateLimitFilter extends OncePerRequestFilter {
 private record Window(long start,int count){}private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();private final int limit;
 public PublicEnquiryRateLimitFilter(int limit){this.limit=limit;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  String path=org.springframework.web.util.UriUtils.decode(req.getRequestURI().substring(req.getContextPath().length()),java.nio.charset.StandardCharsets.UTF_8);
  if("POST".equals(req.getMethod())&&(path.equals("/api/public/rental-enquiries")||path.equals("/api/public/contact-messages"))){
   long now=System.currentTimeMillis()/1000;windows.entrySet().removeIf(e->now-e.getValue().start()>=900);
   if(windows.size()>=10000&&!windows.containsKey(req.getRemoteAddr())){reject(res);return;}
   var window=windows.compute(req.getRemoteAddr(),(k,old)->old==null?new Window(now,1):new Window(old.start(),old.count()+1));if(window.count()>limit){reject(res);return;}
  }chain.doFilter(req,res);
 }
 private void reject(HttpServletResponse res)throws IOException{res.setStatus(429);res.setContentType("application/problem+json");res.setHeader("Retry-After","900");res.getWriter().write("{\"detail\":\"Too many enquiries. Please try again in 15 minutes or call MAK.\"}");}
}
