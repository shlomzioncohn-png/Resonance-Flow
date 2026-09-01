package com.rnd.inbox.service;

import com.rnd.inbox.dto.PageResponseDto;
import com.rnd.inbox.dto.UserCreateRequestDto;
import com.rnd.inbox.dto.UserResponseDto;
import com.rnd.inbox.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.dto.UserUpdateRequestDto;
import com.rnd.inbox.exception.DuplicateEmailException;
import com.rnd.inbox.exception.DuplicateUsernameException;
import com.rnd.inbox.exception.OrganizationNotFoundException;
import com.rnd.inbox.exception.UserNotFoundException;
import com.rnd.inbox.mapper.UserMapper;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.UserStatus;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, OrganizationRepository organizationRepository,
                        UserMapper userMapper) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.userMapper = userMapper;
    }

    @Transactional
    public UserResponseDto createUser(UserCreateRequestDto request) {
        Organization organization = findOrganizationOrThrow(UUID.fromString(request.getOrganizationId()));
        assertEmailAvailable(request.getEmail(), null);
        assertUsernameAvailable(request.getUsername(), null);

        User user = userMapper.toEntity(request, organization);
        User saved = userRepository.save(user);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public UserResponseDto getUserById(UUID id) {
        User user = findUserOrThrow(id);
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID id, UserUpdateRequestDto request) {
        User user = findUserOrThrow(id);
        assertEmailAvailable(request.getEmail(), id);
        assertUsernameAvailable(request.getUsername(), id);

        userMapper.applyUpdate(user, request);
        User saved = userRepository.save(user);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<UserResponseDto> listUsers(Pageable pageable) {
        Page<UserResponseDto> responsePage = userRepository.findAll(pageable).map(userMapper::toResponseDto);
        return PageResponseDto.from(responsePage);
    }

    @Transactional
    public UserResponseDto updateStatus(UUID id, UserStatusUpdateRequestDto request) {
        User user = findUserOrThrow(id);
        userMapper.applyStatusChange(user, UserStatus.valueOf(request.getStatus()));
        User saved = userRepository.save(user);
        return userMapper.toResponseDto(saved);
    }

    private Organization findOrganizationOrThrow(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new OrganizationNotFoundException(id));
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    private void assertEmailAvailable(String email, UUID excludingUserId) {
        if (email == null) {
            return;
        }
        userRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(excludingUserId)) {
                throw new DuplicateEmailException(email);
            }
        });
    }

    private void assertUsernameAvailable(String username, UUID excludingUserId) {
        if (username == null) {
            return;
        }
        userRepository.findByUsername(username).ifPresent(existing -> {
            if (!existing.getId().equals(excludingUserId)) {
                throw new DuplicateUsernameException(username);
            }
        });
    }
}
