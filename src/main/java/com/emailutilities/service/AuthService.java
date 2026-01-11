package com.emailutilities.service;

import com.emailutilities.dto.LoginResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuthService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecaptchaService recaptchaService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginResponse validateLogin(String email, String password, String recaptchaToken) {
        // Verify reCAPTCHA first
        if (recaptchaToken != null && !recaptchaToken.isEmpty()) {
            if (!recaptchaService.verifyToken(recaptchaToken)) {
                return LoginResponse.failure("reCAPTCHA verification failed");
            }
        }

        // Find user by email
        try {
            Map<String, Object> user = jdbcTemplate.queryForMap(
                    "SELECT id, email, display_name, password_hash FROM users WHERE LOWER(email) = LOWER(?)",
                    email
            );

            String storedHash = (String) user.get("password_hash");

            // Verify password
            if (passwordEncoder.matches(password, storedHash)) {
                return LoginResponse.success(
                        ((Number) user.get("id")).longValue(),
                        (String) user.get("display_name"),
                        (String) user.get("email")
                );
            } else {
                return LoginResponse.failure("Invalid email or password");
            }
        } catch (Exception e) {
            return LoginResponse.failure("Invalid email or password");
        }
    }
}
