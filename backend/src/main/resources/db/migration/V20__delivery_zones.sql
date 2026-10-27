-- Delivery zones and charges (implementation-plan.md 6b). A zone lists districts and, more
-- narrowly, areas; an address takes the zone of its area, else of its district, else the
-- business's default zone. Each district and each area is in at most one zone of a business: the
-- link tables' primary keys say so, so "which zone?" always has one answer.
--
-- Businesses get two zones when created (Inside Dhaka ৳70 for Dhaka district, Outside Dhaka ৳130 as
-- the default). None existed outside tests when this was added, so there is no backfill.

CREATE TABLE delivery_zones (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    name        text        NOT NULL,
    -- Poisha.
    charge      bigint      NOT NULL,
    is_default  boolean     NOT NULL,
    position    int         NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,

    CONSTRAINT uq_delivery_zones_business_id UNIQUE (business_id, id),
    CONSTRAINT ck_delivery_zones_name CHECK (length(name) BETWEEN 1 AND 50),
    CONSTRAINT ck_delivery_zones_charge CHECK (charge BETWEEN 0 AND 10000000),
    CONSTRAINT ck_delivery_zones_position CHECK (position >= 0)
);

CREATE UNIQUE INDEX uq_delivery_zones_name ON delivery_zones (business_id, lower(name));
CREATE UNIQUE INDEX uq_delivery_zones_default ON delivery_zones (business_id) WHERE is_default;

SELECT mojud_enable_tenant_rls('delivery_zones');

CREATE TABLE delivery_zone_districts (
    business_id uuid NOT NULL,
    zone_id     uuid NOT NULL,
    district_id int  NOT NULL REFERENCES districts (id),

    PRIMARY KEY (business_id, district_id),
    CONSTRAINT fk_delivery_zone_districts_zone FOREIGN KEY (business_id, zone_id)
        REFERENCES delivery_zones (business_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_delivery_zone_districts_zone ON delivery_zone_districts (zone_id);

SELECT mojud_enable_tenant_rls('delivery_zone_districts');

CREATE TABLE delivery_zone_areas (
    business_id uuid NOT NULL,
    zone_id     uuid NOT NULL,
    area_id     int  NOT NULL REFERENCES areas (id),

    PRIMARY KEY (business_id, area_id),
    CONSTRAINT fk_delivery_zone_areas_zone FOREIGN KEY (business_id, zone_id)
        REFERENCES delivery_zones (business_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_delivery_zone_areas_zone ON delivery_zone_areas (zone_id);

SELECT mojud_enable_tenant_rls('delivery_zone_areas');
