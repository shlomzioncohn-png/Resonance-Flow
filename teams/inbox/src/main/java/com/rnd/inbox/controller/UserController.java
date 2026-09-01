package com.rnd.inbox.controller;

import com.rnd.inbox.dto.PageResponseDto;
import com.rnd.inbox.dto.UserCreateRequestDto;
import com.rnd.inbox.dto.UserResponseDto;
import com.rnd.inbox.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.dto.UserUpdateRequestDto;
import com.rnd.inbox.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody UserCreateRequestDto request,
                                                        UriComponentsBuilder uriBuilder) {
        UserResponseDto created = userService.createUser(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/users/{id}").build(created.getId()))
                .body(created);
    }

    @GetMapping("/{id}")
    public UserResponseDto getUserById(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @GetMapping
    public PageResponseDto<UserResponseDto> listUsers(Pageable pageable) {
        return userService.listUsers(pageable);
    }

    @PutMapping("/{id}")
    public UserResponseDto updateUser(@PathVariable UUID id, @Valid @RequestBody UserUpdateRequestDto request) {
        return userService.updateUser(id, request);
    }

    @PatchMapping("/{id}/status")
    public UserResponseDto updateStatus(@PathVariable UUID id,
                                         @Valid @RequestBody UserStatusUpdateRequestDto request) {
        return userService.updateStatus(id, request);
    }
}
