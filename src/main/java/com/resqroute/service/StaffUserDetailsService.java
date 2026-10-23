package com.resqroute.service;

import com.resqroute.model.StaffUser;
import com.resqroute.repository.StaffUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges the staff_user table into Spring Security. The Dao
 * authentication provider (auto-configured from this bean) verifies
 * the submitted password against the STORED HASH ONLY — plaintext
 * passwords never exist anywhere in the system.
 */
@Service
public class StaffUserDetailsService implements UserDetailsService {

    private final StaffUserRepository staffUserRepository;

    public StaffUserDetailsService(StaffUserRepository staffUserRepository) {
        this.staffUserRepository = staffUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        StaffUser user = staffUserRepository
                .findByUsername(username)
                .orElseThrow(() ->
                        new UsernameNotFoundException("Unknown staff user"));

        String role = (user.getRole() == null || user.getRole().isBlank())
                ? "STAFF"
                : user.getRole();

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(role)
                .build();
    }
}
