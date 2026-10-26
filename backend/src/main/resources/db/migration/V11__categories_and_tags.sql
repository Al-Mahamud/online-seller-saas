-- Categories and tags (implementation-plan.md 3a). Products and product_tags arrive in 3b.

CREATE TABLE categories (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    -- Null for a top-level category. Two levels at most (checked by CategoryService).
    parent_id   uuid,
    name        text        NOT NULL,
    position    int         NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL,

    -- For composite foreign keys: a child can only point at a parent in the same business.
    CONSTRAINT uq_categories_business_id UNIQUE (business_id, id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (business_id, parent_id) REFERENCES categories (business_id, id),
    CONSTRAINT ck_categories_not_own_parent CHECK (parent_id <> id),
    CONSTRAINT ck_categories_name CHECK (length(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_categories_position CHECK (position >= 0)
);

-- Names are unique among siblings, ignoring case.
CREATE UNIQUE INDEX uq_categories_sibling_name
    ON categories (business_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

SELECT mojud_enable_tenant_rls('categories');

CREATE TABLE tags (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL,

    CONSTRAINT uq_tags_business_id UNIQUE (business_id, id),
    CONSTRAINT ck_tags_name CHECK (length(name) BETWEEN 1 AND 50)
);

CREATE UNIQUE INDEX uq_tags_name ON tags (business_id, lower(name));

SELECT mojud_enable_tenant_rls('tags');
