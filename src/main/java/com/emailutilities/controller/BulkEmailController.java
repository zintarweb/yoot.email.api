package com.emailutilities.controller;

import com.emailutilities.entity.EmailAccount;
import com.emailutilities.entity.SegregationHistory;
import com.emailutilities.repository.EmailAccountRepository;
import com.emailutilities.repository.EmailMetadataRepository;
import com.emailutilities.repository.SegregationHistoryRepository;
import com.emailutilities.service.GmailService;
import com.emailutilities.service.OutlookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/bulk")
public class BulkEmailController {

    private final EmailAccountRepository accountRepository;
    private final EmailMetadataRepository metadataRepository;
    private final SegregationHistoryRepository segregationHistoryRepository;
    private final GmailService gmailService;
    private final OutlookService outlookService;

    public BulkEmailController(EmailAccountRepository accountRepository,
                               EmailMetadataRepository metadataRepository,
                               SegregationHistoryRepository segregationHistoryRepository,
                               GmailService gmailService,
                               OutlookService outlookService) {
        this.accountRepository = accountRepository;
        this.metadataRepository = metadataRepository;
        this.segregationHistoryRepository = segregationHistoryRepository;
        this.gmailService = gmailService;
        this.outlookService = outlookService;
    }

    /**
     * Move all emails from specified senders to a folder
     */
    @PostMapping("/move")
    public ResponseEntity<?> moveEmailsBySenders(@RequestBody MoveRequest request) {
        try {
            Long userId = 1L; // TODO: Get from auth
            List<EmailAccount> accounts = accountRepository.findByUserId(userId);

            int totalMoved = 0;
            Map<String, Object> results = new LinkedHashMap<>();

            for (EmailAccount account : accounts) {
                try {
                    int moved = 0;
                    if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                        // Create folder if needed
                        String folderId = request.getFolderId();
                        if (request.isCreateNew() && request.getNewFolderName() != null) {
                            folderId = gmailService.createLabel(account.getId(), request.getNewFolderName());
                        }
                        moved = gmailService.moveEmailsBySenders(account.getId(), request.getSenderEmails(), folderId);
                    } else if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                        String folderId = request.getFolderId();
                        if (request.isCreateNew() && request.getNewFolderName() != null) {
                            folderId = outlookService.createFolder(account.getId(), request.getNewFolderName(), null);
                        }
                        moved = outlookService.moveEmailsBySenders(account.getId(), request.getSenderEmails(), folderId);
                    }
                    totalMoved += moved;
                    String accountKey = account.getEmailAddress() + " (" + account.getProvider() + ")";
                    results.put(accountKey, Map.of("moved", moved, "status", "success"));
                } catch (Exception e) {
                    String accountKey = account.getEmailAddress() + " (" + account.getProvider() + ")";
                    results.put(accountKey, Map.of("moved", 0, "status", "error", "error", e.getMessage()));
                }
            }

            return ResponseEntity.ok(Map.of(
                "totalMoved", totalMoved,
                "results", results
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Segregate emails - create a folder for each sender under "Segregated" root
     */
    @PostMapping("/segregate")
    public ResponseEntity<?> segregateEmailsBySenders(@RequestBody SegregateRequest request) {
        try {
            Long userId = 1L;
            List<EmailAccount> accounts = accountRepository.findByUserId(userId);

            int totalMoved = 0;
            Map<String, Object> results = new LinkedHashMap<>();
            Map<String, Integer> senderTotals = new LinkedHashMap<>();

            for (EmailAccount account : accounts) {
                try {
                    Map<String, Integer> accountResults = new LinkedHashMap<>();

                    if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                        // Ensure "Segregated" parent label exists
                        String parentLabelId = gmailService.getOrCreateLabel(account.getId(), "Segregated");

                        for (String senderEmail : request.getSenderEmails()) {
                            String folderName = sanitizeFolderName(senderEmail);
                            String labelId = gmailService.getOrCreateLabel(account.getId(), "Segregated/" + folderName);
                            int moved = gmailService.moveEmailsBySenders(account.getId(), List.of(senderEmail), labelId);
                            accountResults.put(senderEmail, moved);
                            totalMoved += moved;
                            senderTotals.merge(senderEmail, moved, Integer::sum);
                        }
                    } else if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                        // Ensure "Segregated" parent folder exists
                        String parentFolderId = outlookService.getOrCreateFolder(account.getId(), "Segregated", null);

                        for (String senderEmail : request.getSenderEmails()) {
                            String folderName = sanitizeFolderName(senderEmail);
                            String folderId = outlookService.getOrCreateFolder(account.getId(), folderName, parentFolderId);
                            int moved = outlookService.moveEmailsBySenders(account.getId(), List.of(senderEmail), folderId);
                            accountResults.put(senderEmail, moved);
                            totalMoved += moved;
                            senderTotals.merge(senderEmail, moved, Integer::sum);
                        }
                    }

                    String accountKey = account.getEmailAddress() + " (" + account.getProvider() + ")";
                    results.put(accountKey, Map.of("senders", accountResults, "status", "success"));
                } catch (Exception e) {
                    String accountKey = account.getEmailAddress() + " (" + account.getProvider() + ")";
                    results.put(accountKey, Map.of("status", "error", "error", e.getMessage()));
                }
            }

            // Record segregation history for each sender
            for (String senderEmail : request.getSenderEmails()) {
                int movedForSender = senderTotals.getOrDefault(senderEmail, 0);
                recordSegregation(userId, senderEmail, "Segregated/" + sanitizeFolderName(senderEmail),
                    movedForSender, SegregationHistory.OperationType.SEGREGATE);
            }

            return ResponseEntity.ok(Map.of(
                "totalMoved", totalMoved,
                "results", results
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get list of already-segregated senders for the current user
     */
    @GetMapping("/segregated")
    public ResponseEntity<?> getSegregatedSenders() {
        try {
            Long userId = 1L;
            List<SegregationHistory> history = segregationHistoryRepository.findByUserId(userId);

            List<Map<String, Object>> result = new ArrayList<>();
            for (SegregationHistory h : history) {
                result.add(Map.of(
                    "senderEmail", h.getSenderEmail(),
                    "folderName", h.getFolderName() != null ? h.getFolderName() : "",
                    "emailsMoved", h.getEmailsMoved(),
                    "operationType", h.getOperationType().toString(),
                    "lastRunAt", h.getLastRunAt().toString(),
                    "runCount", h.getRunCount()
                ));
            }

            return ResponseEntity.ok(Map.of(
                "segregatedSenders", result,
                "count", result.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Clear segregation history for a specific sender (for re-processing)
     */
    @DeleteMapping("/segregated/{senderEmail}")
    public ResponseEntity<?> clearSegregationHistory(@PathVariable String senderEmail) {
        try {
            Long userId = 1L;
            Optional<SegregationHistory> existing = segregationHistoryRepository
                .findByUserIdAndSenderEmail(userId, senderEmail.toLowerCase());

            if (existing.isPresent()) {
                segregationHistoryRepository.delete(existing.get());
                return ResponseEntity.ok(Map.of("message", "Cleared segregation history for " + senderEmail));
            } else {
                return ResponseEntity.ok(Map.of("message", "No history found for " + senderEmail));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Record or update segregation history
     */
    private void recordSegregation(Long userId, String senderEmail, String folderName,
                                   int emailsMoved, SegregationHistory.OperationType opType) {
        Optional<SegregationHistory> existing = segregationHistoryRepository
            .findByUserIdAndSenderEmail(userId, senderEmail.toLowerCase());

        if (existing.isPresent()) {
            // Update existing record
            SegregationHistory history = existing.get();
            history.setEmailsMoved(history.getEmailsMoved() + emailsMoved);
            history.setRunCount(history.getRunCount() + 1);
            history.setLastRunAt(LocalDateTime.now());
            history.setFolderName(folderName);
            history.setOperationType(opType);
            segregationHistoryRepository.save(history);
        } else {
            // Create new record
            SegregationHistory history = new SegregationHistory();
            history.setUserId(userId);
            history.setSenderEmail(senderEmail.toLowerCase());
            history.setFolderName(folderName);
            history.setEmailsMoved(emailsMoved);
            history.setOperationType(opType);
            history.setRunCount(1);
            segregationHistoryRepository.save(history);
        }
    }

    /**
     * Debug: Check which account has emails from a specific sender
     */
    @GetMapping("/debug/sender")
    public ResponseEntity<?> debugSenderAccount(@RequestParam String email) {
        String senderEmail = email;
        try {
            List<Object[]> results = metadataRepository.countBySenderAndAccount(senderEmail.toLowerCase());
            List<EmailAccount> accounts = accountRepository.findAll();

            Map<String, Object> accountBreakdown = new LinkedHashMap<>();
            long total = 0;

            for (Object[] row : results) {
                Long accountId = (Long) row[0];
                Long count = (Long) row[1];
                total += count;

                // Find account details
                String accountInfo = "Unknown Account";
                for (EmailAccount acc : accounts) {
                    if (acc.getId().equals(accountId)) {
                        accountInfo = acc.getEmailAddress() + " (" + acc.getProvider() + ")";
                        break;
                    }
                }
                accountBreakdown.put(accountInfo, Map.of(
                    "accountId", accountId,
                    "emailCount", count
                ));
            }

            // Also test Gmail search directly
            for (EmailAccount account : accounts) {
                if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                    int gmailSearchCount = gmailService.countEmailsFromSender(account.getId(), senderEmail);
                    accountBreakdown.put("gmailLiveSearch", gmailSearchCount);
                }
            }

            return ResponseEntity.ok(Map.of(
                "senderEmail", senderEmail,
                "totalEmails", total,
                "byAccount", accountBreakdown
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get folders for all accounts (for folder picker)
     */
    @GetMapping("/folders")
    public ResponseEntity<?> getAllFolders() {
        try {
            Long userId = 1L;
            List<EmailAccount> accounts = accountRepository.findByUserId(userId);

            Map<String, Object> allFolders = new LinkedHashMap<>();

            for (EmailAccount account : accounts) {
                try {
                    List<Map<String, Object>> folders;
                    if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                        folders = gmailService.getFolders(account.getId());
                    } else if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                        folders = outlookService.getFolders(account.getId());
                    } else {
                        folders = List.of();
                    }
                    allFolders.put(account.getEmailAddress(), Map.of(
                        "accountId", account.getId(),
                        "provider", account.getProvider().toString(),
                        "folders", folders
                    ));
                } catch (Exception e) {
                    allFolders.put(account.getEmailAddress(), Map.of("error", e.getMessage()));
                }
            }

            return ResponseEntity.ok(allFolders);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String sanitizeFolderName(String email) {
        // Extract the part before @ and sanitize for folder name
        String name = email.contains("@") ? email.substring(0, email.indexOf("@")) : email;
        // Remove invalid characters
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // Request DTOs
    public static class MoveRequest {
        private List<String> senderEmails;
        private String folderId;
        private boolean createNew;
        private String newFolderName;

        public List<String> getSenderEmails() { return senderEmails; }
        public void setSenderEmails(List<String> senderEmails) { this.senderEmails = senderEmails; }
        public String getFolderId() { return folderId; }
        public void setFolderId(String folderId) { this.folderId = folderId; }
        public boolean isCreateNew() { return createNew; }
        public void setCreateNew(boolean createNew) { this.createNew = createNew; }
        public String getNewFolderName() { return newFolderName; }
        public void setNewFolderName(String newFolderName) { this.newFolderName = newFolderName; }
    }

    public static class SegregateRequest {
        private List<String> senderEmails;

        public List<String> getSenderEmails() { return senderEmails; }
        public void setSenderEmails(List<String> senderEmails) { this.senderEmails = senderEmails; }
    }
}
