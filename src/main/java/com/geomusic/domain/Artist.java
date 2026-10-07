package com.geomusic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Music artist. Stored in the shard of {@link #originRegion}.
 */
@Entity
@Table(name = "artists")
public class Artist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_region", nullable = false, length = 4)
    private Region originRegion;

    @Column(nullable = false, length = 100)
    private String genre;

    protected Artist() {
    }

    public Artist(String name, Region originRegion, String genre) {
        this.name = name;
        this.originRegion = originRegion;
        this.genre = genre;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Region getOriginRegion() {
        return originRegion;
    }

    public String getGenre() {
        return genre;
    }
}
