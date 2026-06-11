package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for user-related operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * Get a user by their ID.
     *
     * @param id the user ID
     * @return the user entity
     * @throws PassboltApiException if the user is not found
     */
    @Transactional(readOnly = true)
    public User getUserById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
