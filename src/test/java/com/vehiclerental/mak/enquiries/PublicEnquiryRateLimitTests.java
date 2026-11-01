package com.vehiclerental.mak.enquiries;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import org.springframework.mock.web.*;
class PublicEnquiryRateLimitTests {
 @Test void limitsCombinedPublicFormsWithoutTrustingForwardedHeadersOrBlockingOtherRoutes()throws Exception{
  var filter=new PublicEnquiryRateLimitFilter(2);int[] accepted={0};
  for(int i=0;i<3;i++){var req=new MockHttpServletRequest("POST",i==1?"/api/public/rental-enquiries":i==2?"/api/public/%63ontact-messages":"/api/public/contact-messages");req.setRemoteAddr("192.0.2.1");req.addHeader("X-Forwarded-For","192.0.2."+(20+i));var res=new MockHttpServletResponse();filter.doFilter(req,res,(a,b)->accepted[0]++);assertEquals(i==2?429:200,res.getStatus());if(i==2)assertEquals("900",res.getHeader("Retry-After"));}
  assertEquals(2,accepted[0]);var res=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/public/vehicles"),res,(a,b)->accepted[0]++);assertEquals(3,accepted[0]);
 }
}
