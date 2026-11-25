-- Second demo source for the combined schema document (graph): a separate
-- schema in the same Postgres, read by the source named "billing".
--
-- Like 01-schema.sql, this only runs on an empty Postgres data directory
-- (see "Known Issues" #5): docker compose down -v, then docker compose up.
--
-- Nothing here is written by the application; the graph and data API only
-- ever read.

CREATE SCHEMA billing;

CREATE TABLE billing.customers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    tax_id VARCHAR(32)
);

-- customer_id is a declared foreign key inside the billing source.
-- user_id deliberately has no foreign key: it mirrors a users row kept in
-- the demo source's public schema, so cross-source inference proposes a
-- billing:invoices.user_id -> demo:users.id relationship that stays
-- "proposed" until confirmed through the overlay endpoints.
CREATE TABLE billing.invoices (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES billing.customers(id),
    user_id BIGINT,
    total NUMERIC(12,2) NOT NULL,
    issued_at TIMESTAMP NOT NULL
);
