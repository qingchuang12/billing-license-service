package com.billing.license.repository;

import com.billing.license.entity.ProductProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductProfileRepository extends JpaRepository<ProductProfile, UUID> {

    Optional<ProductProfile> findByProductCodeIgnoreCase(String productCode);

    boolean existsByProductCodeIgnoreCase(String productCode);
}
