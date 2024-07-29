package com.igot.cb.contentprovider.repository;

import com.igot.cb.contentprovider.entity.ContentPartnerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ContentPartnerRepository extends JpaRepository<ContentPartnerEntity, String>{
    Optional<ContentPartnerEntity> findByIdAndIsActive(String exitingId,Boolean isActive);
}