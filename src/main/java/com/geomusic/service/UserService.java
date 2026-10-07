package com.geomusic.service;

import com.geomusic.api.dto.CreateUserRequest;
import com.geomusic.api.dto.UserResponse;
import com.geomusic.domain.User;
import com.geomusic.domain.repository.UserRepository;
import com.geomusic.error.ResourceNotFoundException;
import com.geomusic.routing.GeoTransactionExecutor;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Observed(name = "geomusic.service")
public class UserService {

    private final UserRepository users;
    private final GeoTransactionExecutor tx;

    public UserService(UserRepository users, GeoTransactionExecutor tx) {
        this.users = users;
        this.tx = tx;
    }

    /**
     * Writes the user to the master of its home region.
     */
    public UserResponse create(CreateUserRequest request) {
        return tx.write(request.homeRegion(), () -> UserResponse.from(
                users.save(new User(request.username().trim(), request.email().trim(), request.homeRegion()))));
    }

    /**
     * Reads the user from the nearest replica (requester's region).
     */
    public UserResponse get(UUID id) {
        return tx.read(() -> users.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", id)));
    }
}
