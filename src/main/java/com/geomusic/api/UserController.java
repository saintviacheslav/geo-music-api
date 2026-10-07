package com.geomusic.api;

import com.geomusic.api.dto.CreateUserRequest;
import com.geomusic.api.dto.FeedResponse;
import com.geomusic.api.dto.UserResponse;
import com.geomusic.service.FeedService;
import com.geomusic.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final FeedService feedService;

    public UserController(UserService userService, FeedService feedService) {
        this.userService = userService;
        this.feedService = feedService;
    }

    /**
     * Creates a user on the master of {@code homeRegion}.
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Reads a user from the nearest replica.
     */
    @GetMapping("/{userId}")
    public UserResponse get(@PathVariable UUID userId) {
        return userService.get(userId);
    }

    /**
     * Album feed of the user, read from the nearest replica of {@code X-User-Region}.
     */
    @GetMapping("/{userId}/feed")
    public FeedResponse feed(@PathVariable UUID userId,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return feedService.feed(userId, limit);
    }
}
