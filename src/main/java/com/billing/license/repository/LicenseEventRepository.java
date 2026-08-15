package com.billing.license.repository;

import com.billing.license.entity.LicenseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LicenseEventRepository extends JpaRepository<LicenseEvent, UUID> {

    List<LicenseEvent> findByLicenseId(UUID licenseId);

    List<LicenseEvent> findByLicenseKey(String licenseKey);
}
