-- Schema matching spring-example/src/main/resources/spring-entities-config.json.
-- The Spring Boot API validates against this schema on startup (ddl-auto=validate)
-- and never creates it itself, so it must exist before the api container starts.

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    is_active BOOLEAN NOT NULL,
    balance NUMERIC(12,2),
    bio TEXT,
    created_at TIMESTAMP
);

CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    body TEXT,
    view_count INTEGER NOT NULL,
    author_id BIGINT NOT NULL REFERENCES users(id),
    published_at TIMESTAMP
);

CREATE TABLE sessions (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    payload JSONB,
    expires_at TIMESTAMP NOT NULL
);

CREATE TABLE post_tags (
    post_id BIGINT NOT NULL,
    tag VARCHAR(255) NOT NULL,
    PRIMARY KEY (post_id, tag)
);
