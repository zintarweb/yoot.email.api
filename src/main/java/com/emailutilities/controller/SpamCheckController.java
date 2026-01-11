package com.emailutilities.controller;

import com.emailutilities.service.SpamCheckService;
import com.emailutilities.service.SpamCheckService.SpamCheckResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/spam")
public class SpamCheckController {

    private final SpamCheckService spamCheckService;

    public SpamCheckController(SpamCheckService spamCheckService) {
        this.spamCheckService = spamCheckService;
    }

    /**
     * Check a single email address against Spamhaus
     */
    @GetMapping("/check/email")
    public ResponseEntity<?> checkEmail(@RequestParam String email) {
        SpamCheckResult result = spamCheckService.checkEmail(email);
        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Check a single domain against Spamhaus DBL
     */
    @GetMapping("/check/domain")
    public ResponseEntity<?> checkDomain(@RequestParam String domain) {
        SpamCheckResult result = spamCheckService.checkDomain(domain);
        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Check a single IP against Spamhaus ZEN
     */
    @GetMapping("/check/ip")
    public ResponseEntity<?> checkIp(@RequestParam String ip) {
        SpamCheckResult result = spamCheckService.checkIp(ip);
        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Batch check multiple emails
     */
    @PostMapping("/check/emails")
    public ResponseEntity<?> checkEmails(@RequestBody List<String> emails) {
        Map<String, SpamCheckResult> results = spamCheckService.checkEmails(emails);

        Map<String, Object> response = results.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().toMap()
            ));

        // Add summary
        long listedCount = results.values().stream().filter(SpamCheckResult::isListed).count();
        response.put("_summary", Map.of(
            "total", emails.size(),
            "listed", listedCount,
            "clean", emails.size() - listedCount
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Get cache statistics
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<?> getCacheStats() {
        return ResponseEntity.ok(spamCheckService.getCacheStats());
    }

    /**
     * Clear the cache
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<?> clearCache() {
        spamCheckService.clearCache();
        return ResponseEntity.ok(Map.of("message", "Cache cleared"));
    }
}
