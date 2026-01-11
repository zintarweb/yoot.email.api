package com.emailutilities.controller;

import com.emailutilities.dto.LoginRequest;
import com.emailutilities.dto.LoginResponse;
import com.emailutilities.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/validate")
    public ResponseEntity<LoginResponse> validateLogin(@RequestBody LoginRequest request) {
        LoginResponse response = authService.validateLogin(
                request.getEmail(),
                request.getPassword(),
                request.getRecaptchaToken()
        );

        if (response.isValid()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(401).body(response);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return validateLogin(request);
    }
}
