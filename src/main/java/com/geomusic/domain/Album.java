package com.geomusic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Album. Stored in the shard of {@link #regionCode}; the artist may belong to another shard.
 */
@Entity
@Table(name = "albums")
public class Album {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artist_id", nullable = false)
    private Artist artist;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "region_code", nullable = false, length = 4)
    private Region regionCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Album() {
    }

    public Album(Artist artist, String title, Integer releaseYear, Region regionCode) {
        this.artist = artist;
        this.title = title;
        this.releaseYear = releaseYear;
        this.regionCode = regionCode;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Artist getArtist() {
        return artist;
    }

    public String getTitle() {
        return title;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public Region getRegionCode() {
        return regionCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
