package com.emailutilities.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class RecaptchaService {

    @Value("${recaptcha.secret-key:}")
    private String secretKey;

    @Value("${recaptcha.verify-url:https://www.google.com/recaptcha/api/siteverify}")
    private String verifyUrl;

    @Value("${recaptcha.score-threshold:0.5}")
    private double scoreThreshold;

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean verifyToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        try {
            String url = verifyUrl + "?secret=" + secretKey + "&response=" + token;
            ResponseEntity<Map> response = restTemplate.postForEntity(url, null, Map.class);

            if (response.getBody() == null) {
                return false;
            }

            Map<String, Object> body = response.getBody();
            Boolean success = (Boolean) body.get("success");

            if (success == null || !success) {
                return false;
            }

            // For reCAPTCHA v3, check the score
            Object scoreObj = body.get("score");
            if (scoreObj != null) {
                double score = ((Number) scoreObj).doubleValue();
                if (score < scoreThreshold) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            System.err.println("reCAPTCHA verification failed: " + e.getMessage());
            return false;
        }
    }
}
