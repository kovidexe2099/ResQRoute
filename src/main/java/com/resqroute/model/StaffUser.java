package com.resqroute.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A Command Center staff account.
 *
 * Passwords are NEVER stored in plaintext: only a salted hash in
 * PasswordEncoder-with-id format (e.g. "{bcrypt}$2a$..."), created
 * by the configured Spring Security PasswordEncoder.
 */
@Entity
public class StaffUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** Salted password hash (never plaintext). */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Role name, e.g. STAFF. */
    private String role;

    private Instant createdAt;

    public StaffUser() {
    }

    public StaffUser(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
