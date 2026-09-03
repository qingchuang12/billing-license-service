package com.billing.license.repository;

import com.billing.license.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByProviderAndProviderSubscriptionId(String provider, String providerSubscriptionId);

    Optional<Subscription> findByLicenseId(UUID licenseId);

    List<Subscription> findByCustomerId(UUID customerId);
}
