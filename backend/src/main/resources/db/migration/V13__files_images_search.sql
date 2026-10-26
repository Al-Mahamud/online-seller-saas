-- Uploaded files, product images, the business logo and product search (implementation-plan.md 3c).

-- An uploaded image after processing: re-encoded (no metadata), stored in two sizes under
-- <business>/<id>/large.<ext> and small.<ext>. The original upload is not kept.
CREATE TABLE uploads (
    id           uuid        PRIMARY KEY,
    business_id  uuid        NOT NULL REFERENCES businesses (id),
    content_type text        NOT NULL,
    width        int         NOT NULL,
    height       int         NOT NULL,
    large_bytes  int         NOT NULL,
    small_bytes  int         NOT NULL,
    uploaded_by  uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL,

    CONSTRAINT uq_uploads_business_id UNIQUE (business_id, id),
    CONSTRAINT ck_uploads_content_type CHECK (content_type IN ('image/jpeg', 'image/png'))
);

SELECT mojud_enable_tenant_rls('uploads');

CREATE TABLE product_images (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL,
    product_id  uuid        NOT NULL,
    file_id     uuid        NOT NULL,
    -- Shown for this variant (a colour, say); null for the whole product.
    variant_id  uuid,
    position    int         NOT NULL,
    created_at  timestamptz NOT NULL,

    CONSTRAINT fk_product_images_product FOREIGN KEY (business_id, product_id) REFERENCES products (business_id, id),
    CONSTRAINT fk_product_images_file FOREIGN KEY (business_id, file_id) REFERENCES uploads (business_id, id),
    CONSTRAINT fk_product_images_variant FOREIGN KEY (business_id, variant_id) REFERENCES product_variants (business_id, id),
    CONSTRAINT uq_product_images_file UNIQUE (product_id, file_id),
    CONSTRAINT ck_product_images_position CHECK (position >= 0)
);

CREATE INDEX ix_product_images_product ON product_images (business_id, product_id, position);

SELECT mojud_enable_tenant_rls('product_images');

ALTER TABLE businesses ADD COLUMN logo_file_id uuid;
ALTER TABLE businesses ADD CONSTRAINT fk_businesses_logo
    FOREIGN KEY (id, logo_file_id) REFERENCES uploads (business_id, id);

-- Search by name (substring and small typos) and SKU. pg_trgm is a trusted extension, so the
-- database owner may create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_product_variants_sku_trgm ON product_variants USING gin (upper(sku) gin_trgm_ops);
