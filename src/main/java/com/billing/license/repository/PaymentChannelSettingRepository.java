package com.billing.license.repository;

import com.billing.license.entity.PaymentChannelSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentChannelSettingRepository extends JpaRepository<PaymentChannelSetting, String> {
}
