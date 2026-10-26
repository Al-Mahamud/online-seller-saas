-- Products, their options and variants, and product tags (implementation-plan.md 3b,
-- backend-plan.md §5.2). Stock levels belong to the inventory module and arrive in 4a.

CREATE TABLE products (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    category_id uuid,
    name        text        NOT NULL,
    description text        NOT NULL DEFAULT '',
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL,

    CONSTRAINT uq_products_business_id UNIQUE (business_id, id),
    -- The category must be in the same business; a used category cannot be deleted.
    CONSTRAINT fk_products_category FOREIGN KEY (business_id, category_id) REFERENCES categories (business_id, id),
    CONSTRAINT ck_products_name CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT ck_products_description CHECK (length(description) <= 5000),
    CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX ix_products_newest ON products (business_id, created_at DESC, id DESC);
CREATE INDEX ix_products_category ON products (business_id, category_id);

SELECT mojud_enable_tenant_rls('products');

-- The options that span a product's variant grid, such as Size: S, M, L. Up to three.
CREATE TABLE product_options (
    id            uuid   PRIMARY KEY,
    business_id   uuid   NOT NULL,
    product_id    uuid   NOT NULL,
    name          text   NOT NULL,
    option_values text[] NOT NULL,
    position      int    NOT NULL,

    CONSTRAINT fk_product_options_product FOREIGN KEY (business_id, product_id) REFERENCES products (business_id, id),
    CONSTRAINT ck_product_options_name CHECK (length(name) BETWEEN 1 AND 30),
    CONSTRAINT ck_product_options_values CHECK (cardinality(option_values) BETWEEN 1 AND 30),
    CONSTRAINT ck_product_options_position CHECK (position BETWEEN 0 AND 2)
);

CREATE UNIQUE INDEX uq_product_options_name ON product_options (product_id, lower(name));

SELECT mojud_enable_tenant_rls('product_options');

-- One sellable combination. Never deleted: archived when its combination leaves the grid and
-- restored, with its SKU and prices, when it comes back.
CREATE TABLE product_variants (
    id                  uuid        PRIMARY KEY,
    business_id         uuid        NOT NULL,
    product_id          uuid        NOT NULL,
    sku                 text        NOT NULL,
    -- Option name -> value, e.g. {"Size": "L", "Color": "Black"}; {} for a product without options.
    attributes          jsonb       NOT NULL DEFAULT '{}',
    -- Whole poisha.
    price               bigint      NOT NULL,
    cost_price          bigint,
    -- Null means the business's default (business_settings.low_stock_default).
    low_stock_threshold int,
    status              text        NOT NULL,
    position            int         NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL,

    CONSTRAINT uq_product_variants_business_id UNIQUE (business_id, id),
    CONSTRAINT fk_product_variants_product FOREIGN KEY (business_id, product_id) REFERENCES products (business_id, id),
    -- Deferred: renaming options rewrites several variants in one transaction.
    CONSTRAINT uq_product_variants_attributes UNIQUE (product_id, attributes) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_product_variants_sku CHECK (length(sku) BETWEEN 1 AND 64 AND sku !~ '\s'),
    CONSTRAINT ck_product_variants_price CHECK (price >= 0),
    CONSTRAINT ck_product_variants_cost CHECK (cost_price >= 0),
    CONSTRAINT ck_product_variants_threshold CHECK (low_stock_threshold BETWEEN 0 AND 100000),
    CONSTRAINT ck_product_variants_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

-- SKUs are unique in a business, ignoring case.
CREATE UNIQUE INDEX uq_product_variants_sku ON product_variants (business_id, upper(sku));
CREATE INDEX ix_product_variants_product ON product_variants (business_id, product_id, position);

SELECT mojud_enable_tenant_rls('product_variants');

CREATE TABLE product_tags (
    business_id uuid NOT NULL,
    product_id  uuid NOT NULL,
    tag_id      uuid NOT NULL,

    PRIMARY KEY (product_id, tag_id),
    CONSTRAINT fk_product_tags_product FOREIGN KEY (business_id, product_id) REFERENCES products (business_id, id),
    -- Deleting a tag takes it off every product.
    CONSTRAINT fk_product_tags_tag FOREIGN KEY (business_id, tag_id) REFERENCES tags (business_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_product_tags_tag ON product_tags (business_id, tag_id);

SELECT mojud_enable_tenant_rls('product_tags');
