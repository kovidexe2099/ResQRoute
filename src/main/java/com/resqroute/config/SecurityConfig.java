package com.resqroute.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security architecture of the final ResQRoute:
 *
 * PUBLIC (no login)
 *   victim.html / track.html / shared css+js   — victim experience
 *   POST /route/best-ambulance                  — victims request help
 *   GET  /track/{token}                         — private victim tracking,
 *                                                 authorized by the
 *                                                 random tracking token
 *   /index.html                                 — the staff LOGIN page
 *
 * STAFF ONLY (authenticated session)
 *   command.html and every management/operational API:
 *   /ambulance/**, /hospital/**, /route/latest,
 *   /osrm/** — routing diagnostics and operational endpoints.
 *
 * Authentication: session-based form login (Spring Security) with
 * BCrypt-hashed passwords from the staff_user table. httpBasic is
 * additionally enabled for programmatic clients (tests, tooling)
 * that send explicit credentials.
 *
 * CSRF: enabled with a readable XSRF-TOKEN cookie for the
 * session-authenticated browser calls (the Command Center JS and the
 * login form send the matching X-XSRF-TOKEN / _csrf value). Excluded:
 *   - /route/best-ambulance: an unauthenticated victim call with no
 *     session — CSRF protection adds nothing there;
 *   - requests with explicit Basic credentials: CSRF protects
 *     against AMBIENT browser state (cookies), not per-request
 *     credentials.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** API prefixes answered with 401 instead of a login redirect. */
    private static final String[] API_PREFIXES = {
            "/ambulance", "/hospital", "/route", "/graph", "/osrm"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        // produces and matches "{bcrypt}$2a$..." hashes — no plaintext
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * "Not authenticated" answer, shared by the authentication entry
     * point AND the access-denied handler.
     *
     * Spring Security 7 no longer adds an anonymous authentication
     * token, so an unauthenticated request that is denied lands at
     * the access-denied handler instead of the entry point. Wiring
     * both to the same logic keeps the behavior identical on every
     * path:
     *   - staff API prefixes -> clean 401 (JSON)
     *   - staff pages       -> redirect to the login page
     *   - an actually-authenticated user that is still denied ->
     *     403 (cannot happen with the single-role setup today)
     */
    private static void respondUnauthenticated(HttpServletRequest request,
                                               HttpServletResponse response)
            throws IOException {

        if (request.getUserPrincipal() != null) {
            // authenticated but genuinely not allowed
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String path = request.getRequestURI();
        for (String prefix : API_PREFIXES) {
            if (path.startsWith(prefix)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }

        response.sendRedirect(request.getContextPath() + "/index.html");
    }

    /**
     * Touches the deferred CSRF token on every response so that
     * CookieCsrfTokenRepository actually writes the XSRF-TOKEN
     * cookie. Without this, the token stays deferred (Spring Security
     * 6.1+ / 7) and a browser session would receive no cookie to
     * echo back in the X-XSRF-TOKEN header.
     */
    private static final class CsrfCookieWriterFilter
            extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException, IOException {

            CsrfToken csrfToken = (CsrfToken) request.getAttribute(
                    CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();   // resolve -> cookie is written
            }
            chain.doFilter(request, response);
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        // plain (non-XOR) token handling: the JS reads the cookie and
        // echoes the exact value back in the X-XSRF-TOKEN header
        CsrfTokenRequestAttributeHandler csrfHandler =
                new CsrfTokenRequestAttributeHandler();

        RequestMatcher basicAuthRequests = request -> {
            String header = request.getHeader("Authorization");
            return header != null && header.startsWith("Basic ");
        };

        http
            .authorizeHttpRequests(auth -> auth

                // ---- public victim experience + staff login page ----
                .requestMatchers(
                        "/",
                        "/index.html",
                        "/victim.html",
                        "/track.html",
                        "/css/**",
                        "/js/**",
                        "/error")
                    .permitAll()

                // ---- victim APIs ----
                .requestMatchers("/route/best-ambulance", "/track/**")
                    .permitAll()

                // ---- everything else is staff only ----
                .anyRequest()
                    .authenticated())

            .formLogin(form -> form
                .loginPage("/index.html")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/command.html", true)
                .failureUrl("/index.html?error")
                .permitAll())

            .httpBasic(basic -> basic.realmName("ResQRoute"))

            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/index.html?logout")
                .permitAll())

            .csrf(csrf -> csrf
                .csrfTokenRepository(
                        org.springframework.security.web.csrf
                                .CookieCsrfTokenRepository
                                .withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers("/route/best-ambulance")
                .ignoringRequestMatchers(basicAuthRequests))

            .addFilterAfter(new CsrfCookieWriterFilter(),
                    BasicAuthenticationFilter.class)

            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) ->
                        respondUnauthenticated(request, response))
                .accessDeniedHandler((request, response, e) ->
                        respondUnauthenticated(request, response)));

        return http.build();
    }
}
