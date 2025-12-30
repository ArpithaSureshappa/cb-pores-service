package com.igot.cb.contentpartner.repository;

import com.igot.cb.contentpartner.entity.ContentPartnerRegistrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContentPartnerRegistrationRepository extends JpaRepository<ContentPartnerRegistrationEntity, String> {
    @Query(value = "SELECT * FROM content_partner_registration WHERE data->>'contentPartnerName' = :contentPartnerName", nativeQuery = true)
    Optional<ContentPartnerRegistrationEntity> findByContentPartnerOrganizationName(@Param("contentPartnerName") String contentPartnerName);
    @Query(value = "SELECT * FROM content_partner_registration WHERE data->>'email' = :email", nativeQuery = true)
    Optional<ContentPartnerRegistrationEntity> findByContentPartnerEmail(@Param("email") String email);
    Optional<ContentPartnerRegistrationEntity> findById(String id);
}