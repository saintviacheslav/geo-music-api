package com.geomusic.domain.repository;

import com.geomusic.domain.Album;
import com.geomusic.domain.Region;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlbumRepository extends JpaRepository<Album, UUID> {

    @Query("select a from Album a join fetch a.artist where a.id = :id")
    Optional<Album> findWithArtistById(@Param("id") UUID id);

    @Query("select a from Album a join fetch a.artist where a.id in :ids")
    List<Album> findAllWithArtistByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Latest albums of the given genres, excluding albums the user already likes.
     */
    @Query("""
            select a from Album a join fetch a.artist ar
            where ar.genre in :genres and a.id not in :excluded
            order by a.createdAt desc""")
    List<Album> findRecommendations(@Param("genres") Collection<String> genres,
                                    @Param("excluded") Collection<UUID> excluded,
                                    Pageable page);

    @Query("select a from Album a join fetch a.artist where a.regionCode = :region order by a.createdAt desc")
    List<Album> findLatestInRegion(@Param("region") Region region, Pageable page);
}
