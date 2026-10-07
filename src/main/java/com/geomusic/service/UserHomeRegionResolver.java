package com.geomusic.service;

import com.geomusic.domain.Region;
import com.geomusic.domain.repository.UserRepository;
import com.geomusic.error.ResourceNotFoundException;
import com.geomusic.routing.GeoTransactionExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the shard (home region) of a user, needed to route writes that only carry a user id.
 *
 * <p>Lookup order: cache → nearest replica → masters, starting with the requester's region. Masters are
 * consulted only when replicas do not know the user yet (just created in another region, replication lag).
 * The home region never changes, so results are cached.
 */
@Component
public class UserHomeRegionResolver {

    private static final int MAX_CACHE_SIZE = 100_000;

    private final UserRepository users;
    private final GeoTransactionExecutor tx;
    private final Map<UUID, Region> cache = new ConcurrentHashMap<>();

    public UserHomeRegionResolver(UserRepository users, GeoTransactionExecutor tx) {
        this.users = users;
        this.tx = tx;
    }

    public Region resolve(UUID userId) {
        Region cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        Region region = tx.read(() -> users.findHomeRegionById(userId))
                .or(() -> lookupOnMasters(userId))
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.clear();
        }
        cache.put(userId, region);
        return region;
    }

    private Optional<Region> lookupOnMasters(UUID userId) {
        Region local = tx.currentUserRegion();
        List<Region> order = new ArrayList<>(List.of(Region.values()));
        order.remove(local);
        order.addFirst(local);

        RuntimeException firstFailure = null;
        for (Region region : order) {
            try {
                Optional<Region> found = tx.readFromMaster(region, () -> users.findHomeRegionById(userId));
                if (found.isPresent()) {
                    return found;
                }
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return Optional.empty();
    }
}
