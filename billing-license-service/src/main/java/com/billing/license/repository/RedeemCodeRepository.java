package com.billing.license.repository;

import com.billing.license.entity.RedeemCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RedeemCodeRepository extends JpaRepository<RedeemCode, UUID> {
    Optional<RedeemCode> findByCode(String code);
    boolean existsByCode(String code);
}
