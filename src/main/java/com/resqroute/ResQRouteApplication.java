package com.resqroute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ResQRouteApplication {

    public static void main(String[] args) {
        failWhenDatabasePasswordIsMissing();
        SpringApplication.run(ResQRouteApplication.class, args);
    }

    /**
     * The MySQL password is intentionally NOT stored in the repository:
     * it must be supplied through the DB_PASSWORD environment variable
     * (see application.properties). Without it, start-up must fail
     * with a clear message instead of an obscure database error.
     */
    private static void failWhenDatabasePasswordIsMissing() {
        String password = System.getenv("DB_PASSWORD");

        if (password == null || password.isBlank()) {
            System.err.println(
                "ERROR: the DB_PASSWORD environment variable is not set.");
            System.err.println(
                "       The MySQL password is supplied only via DB_PASSWORD");
            System.err.println(
                "       and is never stored in this repository.");
            System.err.println(
                "       PowerShell:   $env:DB_PASSWORD=\"...\"; "
                    + ".\\mvnw.cmd spring-boot:run");
            System.err.println(
                "       cmd:          set DB_PASSWORD=... "
                    + "&& mvnw.cmd spring-boot:run");

            System.exit(1);
        }
    }
}
