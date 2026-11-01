package com.vehiclerental.mak.enquiries;
import org.springframework.data.jpa.repository.JpaRepository;import java.util.List;
public interface EnquiryEventRepository extends JpaRepository<EnquiryEvent,Long> {
 List<EnquiryEvent> findByEnquiryIdOrderByIdDesc(long enquiryId);
}
