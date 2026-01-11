package com.emailutilities.config;

import com.emailutilities.entity.User;
import com.emailutilities.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initData(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            // Create jlee user for development if not exists
            if (userRepository.findByEmail("zintarweb@gmail.com").isEmpty()) {
                User jleeUser = new User();
                jleeUser.setEmail("zintarweb@gmail.com");
                jleeUser.setPasswordHash(passwordEncoder.encode("dev123"));
                jleeUser.setDisplayName("John Lee");
                userRepository.save(jleeUser);
                System.out.println("Created jlee user with ID: " + jleeUser.getId());
            }

            // Create drady user for development if not exists
            if (userRepository.findByEmail("drady@example.com").isEmpty()) {
                User dradyUser = new User();
                dradyUser.setEmail("drady@example.com");
                dradyUser.setPasswordHash(passwordEncoder.encode("dev123"));
                dradyUser.setDisplayName("D Rady");
                userRepository.save(dradyUser);
                System.out.println("Created drady user with ID: " + dradyUser.getId());
            }
        };
    }
}
