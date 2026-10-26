package com.mojud.catalog.product;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product_options")
class ProductOption {

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

    UUID getId() {
        return id;
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
