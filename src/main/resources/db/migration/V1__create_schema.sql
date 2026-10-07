-- Schema of one regional master. Each master owns the rows of its region (its shard) and receives the
-- other regions' rows through logical replication (see infra/postgres/setup-logical-replication.sh).
--
-- Rules imposed by multi-master logical replication:
--   * no foreign keys across shards: a referenced row may live on another master and arrive later
--     (albums.artist_id, user_favorite_albums.album_id are therefore not constrained);
--   * no global unique constraints besides UUID primary keys: the same username registered in two regions
--     would make the replication apply worker fail and stop the subscription.

CREATE TABLE artists
(
    id            UUID PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    origin_region VARCHAR(4)   NOT NULL CHECK (origin_region IN ('AMER', 'EURO', 'ASIA')),
    genre         VARCHAR(100) NOT NULL
);

CREATE INDEX idx_artists_genre ON artists (genre);

CREATE TABLE albums
(
    id           UUID PRIMARY KEY,
    artist_id    UUID         NOT NULL,
    title        VARCHAR(300) NOT NULL,
    release_year INTEGER CHECK (release_year BETWEEN 1900 AND 2100),
    region_code  VARCHAR(4)   NOT NULL CHECK (region_code IN ('AMER', 'EURO', 'ASIA')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_albums_artist ON albums (artist_id);
CREATE INDEX idx_albums_region_created ON albums (region_code, created_at DESC);
CREATE INDEX idx_albums_created ON albums (created_at DESC);

CREATE TABLE users
(
    id          UUID PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    email       VARCHAR(255) NOT NULL,
    home_region VARCHAR(4)   NOT NULL CHECK (home_region IN ('AMER', 'EURO', 'ASIA')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_home_region ON users (home_region);

-- A favorite is written to the master of the user's home region, where the user row always exists,
-- so this foreign key never crosses shards.
CREATE TABLE user_favorite_albums
(
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    album_id    UUID        NOT NULL,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_region VARCHAR(4)  NOT NULL CHECK (user_region IN ('AMER', 'EURO', 'ASIA')),
    PRIMARY KEY (user_id, album_id)
);

CREATE INDEX idx_favorites_user_added ON user_favorite_albums (user_id, added_at DESC);
