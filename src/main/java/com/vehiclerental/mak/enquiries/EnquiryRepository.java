package com.vehiclerental.mak.enquiries;
import org.springframework.data.jpa.repository.*;import java.util.Optional;
public interface EnquiryRepository extends JpaRepository<WebsiteEnquiry,Long>,JpaSpecificationExecutor<WebsiteEnquiry> {
 Optional<WebsiteEnquiry> findBySubmissionId(String submissionId);
}
