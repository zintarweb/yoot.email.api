package com.emailutilities.dto;

public class LoginRequest {
    private String email;
    private String password;
    private String recaptchaToken;

    public LoginRequest() {}

    public LoginRequest(String email, String password, String recaptchaToken) {
        this.email = email;
        this.password = password;
        this.recaptchaToken = recaptchaToken;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRecaptchaToken() { return recaptchaToken; }
    public void setRecaptchaToken(String recaptchaToken) { this.recaptchaToken = recaptchaToken; }
}
