package com.mojud.customers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** {@code phone} in E.164. */
    Optional<Customer> findByPhone(String phone);

    /** Newest first, after the cursor position; the first page passes a position after everything. */
    @Query("""
            select c from Customer c
            where c.createdAt < :afterAt or (c.createdAt = :afterAt and c.id < :afterId)
            order by c.createdAt desc, c.id desc""")
    List<Customer> findPage(Instant afterAt, UUID afterId, Limit limit);

    /**
     * Customers whose phone contains {@code digits}, or whose name contains {@code pattern} (a LIKE
     * pattern, ignoring case) or is close to {@code q} (small typos); best matches first. Plain SQL
     * gets no Hibernate tenant filter, so the business is passed explicitly (row-level security
     * also applies). {@code digits} is null-safe: pass "" for "no phone search".
     */
    @Query(nativeQuery = true, value = """
            select c.* from customers c
            where c.business_id = :businessId
              and ((:digits <> '' and c.phone like '%' || :digits || '%')
                   or lower(c.name) like :pattern
                   or word_similarity(lower(:q), lower(c.name)) >= 0.4)
            order by (:digits <> '' and c.phone like '%' || :digits || '%') desc,
                     (lower(c.name) like :pattern) desc,
                     word_similarity(lower(:q), lower(c.name)) desc,
                     c.created_at desc
            limit :limit""")
    List<Customer> search(UUID businessId, String q, String pattern, String digits, int limit);
}
