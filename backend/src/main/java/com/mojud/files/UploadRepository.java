package com.mojud.files;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface UploadRepository extends JpaRepository<Upload, UUID> {
}
