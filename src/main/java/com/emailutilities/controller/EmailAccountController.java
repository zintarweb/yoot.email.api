package com.emailutilities.controller;

import com.emailutilities.entity.EmailAccount;
import com.emailutilities.repository.EmailAccountRepository;
import com.emailutilities.repository.UserRepository;
import com.emailutilities.service.GmailService;
import com.emailutilities.service.OutlookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class EmailAccountController {

    private final EmailAccountRepository emailAccountRepository;
    private final UserRepository userRepository;
    private final GmailService gmailService;
    private final OutlookService outlookService;

    public EmailAccountController(EmailAccountRepository emailAccountRepository,
                                  UserRepository userRepository,
                                  GmailService gmailService,
                                  OutlookService outlookService) {
        this.emailAccountRepository = emailAccountRepository;
        this.userRepository = userRepository;
        this.gmailService = gmailService;
        this.outlookService = outlookService;
    }

    @GetMapping
    public ResponseEntity<?> listAccounts(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-Id header is required"));
        }
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        List<EmailAccount> accounts = emailAccountRepository.findByUserId(userId);
        return ResponseEntity.ok(accounts);
    }

    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody Map<String, Object> request) {
        // For now, just return success - full implementation later
        String emailAddress = (String) request.get("emailAddress");
        String provider = (String) request.get("provider");

        EmailAccount account = new EmailAccount();
        account.setEmailAddress(emailAddress);
        account.setProvider(EmailAccount.EmailProvider.valueOf(provider));
        account.setSyncStatus(EmailAccount.SyncStatus.PENDING);

        // Note: In real implementation, need to associate with User
        // For now, just acknowledge the request
        return ResponseEntity.ok(Map.of(
            "message", "Account creation initiated",
            "email", emailAddress,
            "provider", provider,
            "note", "OAuth flow not yet implemented"
        ));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<?> syncAccount(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
            "message", "Sync initiated",
            "accountId", id
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAccount(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            EmailAccount account = emailAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));

            // Verify the account belongs to the user
            if (userId != null && !account.getUser().getId().equals(userId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Account does not belong to this user"));
            }

            emailAccountRepository.delete(account);
            return ResponseEntity.ok(Map.of(
                "message", "Account deleted successfully",
                "accountId", id
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/emails")
    public ResponseEntity<?> getEmails(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int maxResults,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String after) {
        try {
            EmailAccount account = emailAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));

            Map<String, Object> result;
            if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                result = outlookService.fetchInbox(id, maxResults, pageToken, before, after);
            } else if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                result = gmailService.fetchInbox(id, maxResults, pageToken, before, after);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider not supported: " + account.getProvider()
                ));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/emails/{messageId}")
    public ResponseEntity<?> getEmail(
            @PathVariable Long id,
            @PathVariable String messageId) {
        try {
            EmailAccount account = emailAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));

            Map<String, Object> email;
            if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                email = outlookService.fetchEmail(id, messageId);
            } else if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                email = gmailService.fetchEmail(id, messageId);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider not supported: " + account.getProvider()
                ));
            }
            return ResponseEntity.ok(email);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/folders")
    public ResponseEntity<?> getFolders(@PathVariable Long id) {
        try {
            EmailAccount account = emailAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));

            List<Map<String, Object>> folders;
            if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                folders = outlookService.fetchFolders(id);
            } else if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                folders = gmailService.fetchLabels(id);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider not supported: " + account.getProvider()
                ));
            }
            return ResponseEntity.ok(folders);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/folders/{folderId}/emails")
    public ResponseEntity<?> getEmailsByFolder(
            @PathVariable Long id,
            @PathVariable String folderId,
            @RequestParam(defaultValue = "20") int maxResults) {
        try {
            EmailAccount account = emailAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));

            Map<String, Object> result;
            if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                result = outlookService.fetchByFolder(id, folderId, maxResults);
            } else if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                result = gmailService.fetchByLabel(id, folderId, maxResults);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider not supported: " + account.getProvider()
                ));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/emails/{messageId}/move")
    public ResponseEntity<?> moveEmail(
            @PathVariable Long id,
            @PathVariable String messageId,
            @RequestBody Map<String, String> request) {
        try {
            EmailAccount account = emailAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));

            String toFolderId = request.get("toFolderId");
            String fromFolderId = request.get("fromFolderId");

            if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                outlookService.moveEmail(id, messageId, toFolderId);
            } else if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                gmailService.moveEmail(id, messageId, fromFolderId, toFolderId);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider not supported: " + account.getProvider()
                ));
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }
}
