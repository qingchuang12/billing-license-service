package com.billing.license.repository;

import com.billing.license.entity.PaymentFeatureSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentFeatureSettingRepository extends JpaRepository<PaymentFeatureSetting, String> {
}
