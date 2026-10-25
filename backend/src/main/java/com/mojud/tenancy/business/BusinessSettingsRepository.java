package com.mojud.tenancy.business;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BusinessSettingsRepository extends JpaRepository<BusinessSettings, UUID> {
}
