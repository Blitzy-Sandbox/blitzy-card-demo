package com.carddemo.repository;

import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.key.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the DisclosureGroup entity (re-platforms the
 * VSAM DISCGRP KSDS). Provides composite-keyed lookup by DisclosureGroupId
 * (account-group id + transaction-type code + transaction-category code) via
 * inherited JpaRepository operations.
 *
 * <p>Interest-rate resolution uses a two-step lookup performed by the calling
 * service: first by the account's own group id, then, when no record is found,
 * by the reserved account-group id {@code "DEFAULT"}. The fallback is a service
 * responsibility; this repository exposes only the keyed accessors.
 */
@Repository
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroupId> {
}
