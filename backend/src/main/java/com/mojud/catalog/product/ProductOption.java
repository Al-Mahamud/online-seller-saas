package com.mojud.catalog.product;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "product_options")
class ProductOption implements Persistable<UUID> {

    /** New until saved or loaded, so Spring Data inserts it without looking it up first. */
    @Transient
    private boolean isNew = true;

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID productId;

    private String name;

    @Column(name = "option_values")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] values;

    private int position;

    protected ProductOption() {
    }

    ProductOption(UUID productId, String name, List<String> values, int position) {
        this.id = Ids.newId();
        this.productId = productId;
        change(name, values, position);
    }

    void change(String newName, List<String> newValues, int newPosition) {
        name = newName;
        values = newValues.toArray(String[]::new);
        position = newPosition;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    String getName() {
        return name;
    }

    List<String> getValues() {
        return List.of(values);
    }

    int getPosition() {
        return position;
    }
}
