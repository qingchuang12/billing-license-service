package com.billing.license.repository;

import com.billing.license.entity.Subscription;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByProviderAndProviderSubscriptionId(PaymentMethod provider, String providerSubscriptionId);

    Optional<Subscription> findByLicenseId(UUID licenseId);

    List<Subscription> findByCustomerId(UUID customerId);

    // 用户自助查询：本人名下订阅，创建时间倒序（最新的在前）
    List<Subscription> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}
