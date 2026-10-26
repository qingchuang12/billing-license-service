package com.billing.license.repository;

import com.billing.license.entity.LicenseEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface LicenseEventRepository extends JpaRepository<LicenseEvent, UUID> {

    List<LicenseEvent> findByLicenseId(UUID licenseId);

    List<LicenseEvent> findByLicenseKey(String licenseKey);

    /** #13 保留期清理：按保留期截断点取一批待删记录。 */
    List<LicenseEvent> findByCreatedAtBefore(LocalDateTime cutoff, Pageable pageable);
}
