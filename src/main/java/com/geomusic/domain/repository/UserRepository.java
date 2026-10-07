package com.geomusic.domain.repository;

import com.geomusic.domain.Region;
import com.geomusic.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u.homeRegion from User u where u.id = :id")
    Optional<Region> findHomeRegionById(@Param("id") UUID id);
}
