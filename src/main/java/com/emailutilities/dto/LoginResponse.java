package com.emailutilities.dto;

public class LoginResponse {
    private boolean valid;
    private Long userId;
    private String displayName;
    private String email;
    private String message;

    public LoginResponse() {}

    public static LoginResponse success(Long userId, String displayName, String email) {
        LoginResponse response = new LoginResponse();
        response.valid = true;
        response.userId = userId;
        response.displayName = displayName;
        response.email = email;
        return response;
    }

    public static LoginResponse failure(String message) {
        LoginResponse response = new LoginResponse();
        response.valid = false;
        response.message = message;
        return response;
    }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
