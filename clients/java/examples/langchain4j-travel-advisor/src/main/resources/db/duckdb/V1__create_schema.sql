CREATE TABLE destinations (
    id BIGINT PRIMARY KEY,
    name VARCHAR NOT NULL,
    region VARCHAR NOT NULL,
    description VARCHAR NOT NULL,
    description_embedding FLOAT[1536],
    longitude DOUBLE NOT NULL,
    latitude DOUBLE NOT NULL
);

CREATE TABLE hotels (
    id BIGINT PRIMARY KEY,
    destination_id BIGINT NOT NULL,
    name VARCHAR NOT NULL,
    price_per_night DECIMAL(10, 2) NOT NULL,
    description VARCHAR NOT NULL,
    description_embedding FLOAT[1536],
    longitude DOUBLE NOT NULL,
    latitude DOUBLE NOT NULL
);

CREATE TABLE activities (
    id BIGINT PRIMARY KEY,
    destination_id BIGINT NOT NULL,
    name VARCHAR NOT NULL,
    season VARCHAR NOT NULL,
    description VARCHAR NOT NULL,
    description_embedding FLOAT[1536],
    longitude DOUBLE NOT NULL,
    latitude DOUBLE NOT NULL
);

CREATE TABLE chat_memory (
    memory_id VARCHAR PRIMARY KEY,
    content VARCHAR NOT NULL,
    title VARCHAR,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE SEQUENCE wishlist_id_seq START 1;

CREATE TABLE wishlist_items (
    id BIGINT PRIMARY KEY DEFAULT nextval('wishlist_id_seq'),
    conversation_id VARCHAR NOT NULL,
    item_type VARCHAR NOT NULL,
    item_id BIGINT NOT NULL,
    UNIQUE (conversation_id, item_type, item_id)
);
