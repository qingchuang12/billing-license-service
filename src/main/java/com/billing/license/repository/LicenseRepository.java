package com.billing.license.repository;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public interface LicenseRepository extends JpaRepository<License, UUID> {
    Optional<License> findByLicenseKey(String licenseKey);
    List<License> findByCustomerId(UUID customerId);
    List<License> findByOrder(Order order);
    boolean existsByLicenseKey(String licenseKey);
}
