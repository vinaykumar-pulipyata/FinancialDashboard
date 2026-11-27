package com.learning.spring.financialdashboard.config;

import com.learning.spring.financialdashboard.service.CredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * One user, HTTP Basic. The browser shows its own login prompt and then sends the credentials with every request,
 * so the front end needs no login page. The password is set in the app on first run and kept as a BCrypt hash in
 * the database (see CredentialService). While no password exists everything is open so the dashboard can ask for one.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CredentialService credentials, LoginThrottle throttle,
                                            @Value("${app.host:finance.localhost}") String appHost) throws Exception {
        RequestMatcher noPasswordYet = request -> !credentials.isSet();
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(noPasswordYet).permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            // Requests for any other Host (DNS rebinding) and password guessing during a lock-out never reach the rules below.
            .addFilterBefore(new AllowedHostFilter(appHost), BasicAuthenticationFilter.class)
            .addFilterBefore(new LoginThrottleFilter(throttle), BasicAuthenticationFilter.class)
            .addFilterBefore(new NoPasswordYetFilter(credentials), BasicAuthenticationFilter.class)
            // A change must say it comes from the dashboard: another site's page cannot add this header.
            .addFilterAfter(new RequireClientHeaderFilter(), AuthorizationFilter.class)
            // The Daily Calendar Notes are sealed with a key made from the password, which arrives with every request.
            .addFilterAfter(new DiaryKeyFilter(), AuthorizationFilter.class)
            // Stateless Basic auth with no cookie session, and the app only listens on localhost. The built-in CSRF check
            // needs a cookie session, so cross-site requests are stopped by RequireClientHeaderFilter instead.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Reads the hash on every login, so a changed or reset password takes effect at once. */
    @Bean
    UserDetailsService userDetailsService(CredentialService credentials) {
        return username -> credentials.hash()
            .filter(hash -> CredentialService.USERNAME.equals(username))
            .map(hash -> User.withUsername(CredentialService.USERNAME).password(hash).roles("USER").build())
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
