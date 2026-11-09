package com.mojud.customers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** Atomic, and without touching the version: orders count while staff edit the customer. */
    @Modifying
    @Query("""
            update Customer c set c.orderCount = c.orderCount + 1,
                   c.lastOrderAt = case when c.lastOrderAt is null or c.lastOrderAt < :at then :at else c.lastOrderAt end
            where c.id = :id""")
    void addOrder(UUID id, Instant at);

    @Modifying
    @Query("update Customer c set c.orderCount = c.orderCount - 1 where c.id = :id and c.orderCount > 0")
    void removeOrder(UUID id);

    /** {@code phone} in E.164. */
    Optional<Customer> findByPhone(String phone);

    /** Newest first. */
    @Query("select c from Customer c order by c.createdAt desc, c.id desc")
    Page<Customer> findPage(Pageable pageable);

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
                   or search_text(c.name) like :pattern
                   or word_similarity(lower(:q), search_text(c.name)) >= 0.4)
            order by (:digits <> '' and c.phone like '%' || :digits || '%') desc,
                     (search_text(c.name) like :pattern) desc,
                     word_similarity(lower(:q), search_text(c.name)) desc,
                     c.created_at desc, c.id desc""", countQuery = """
            select count(*) from customers c
            where c.business_id = :businessId
              and ((:digits <> '' and c.phone like '%' || :digits || '%')
                   or search_text(c.name) like :pattern
                   or word_similarity(lower(:q), search_text(c.name)) >= 0.4)""")
    Page<Customer> search(UUID businessId, String q, String pattern, String digits, Pageable pageable);
}
