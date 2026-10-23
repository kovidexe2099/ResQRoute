package com.resqroute.service;

import com.resqroute.model.StaffUser;
import com.resqroute.repository.StaffUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Seeds the first staff account on first start-up.
 *
 * No plaintext password is ever stored in a file:
 *   - if resqroute.staff.admin-password-hash is configured (a
 *     PasswordEncoder-with-id hash such as "{bcrypt}$2a$..."),
 *     that hash is used;
 *   - otherwise a random password is generated, bcrypt-hashed for
 *     storage and printed ONCE in the console log.
 *
 * Existing accounts are never touched. To reset the admin, delete
 * its staff_user row and restart.
 */
@Component
public class StaffUserSeeder implements CommandLineRunner {

    private static final Logger log =
            LoggerFactory.getLogger(StaffUserSeeder.class);

    /** Ambiguous characters (0/O, 1/l/I) excluded for readability. */
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 16;

    private final StaffUserRepository staffUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPasswordHash;

    public StaffUserSeeder(
            StaffUserRepository staffUserRepository,
            PasswordEncoder passwordEncoder,
            @Value("${resqroute.staff.admin-username:admin}") String adminUsername,
            @Value("${resqroute.staff.admin-password-hash:}") String adminPasswordHash) {
        this.staffUserRepository = staffUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPasswordHash = adminPasswordHash;
    }

    @Override
    public void run(String... args) {
        if (staffUserRepository.count() > 0) {
            return;   // accounts already exist — never touch them
        }

        if (adminPasswordHash != null && !adminPasswordHash.isBlank()) {
            staffUserRepository.save(new StaffUser(
                    adminUsername, adminPasswordHash, "STAFF"));
            log.info("Staff account '{}' created from the configured "
                    + "password hash", adminUsername);
            return;
        }

        String generated = generatePassword();

        staffUserRepository.save(new StaffUser(
                adminUsername, passwordEncoder.encode(generated), "STAFF"));

        log.warn("=====================================================");
        log.warn(" STAFF LOGIN created (first run only)");
        log.warn("   username : {}", adminUsername);
        log.warn("   password : {}", generated);
        log.warn(" Shown ONCE. To reset it, delete the staff_user row");
        log.warn(" for this user and restart the application.");
        log.warn("=====================================================");
    }

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET.charAt(
                    random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }
}
