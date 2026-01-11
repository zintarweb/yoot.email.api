package com.emailutilities.repository;

import com.emailutilities.entity.EmailMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailMetadataRepository extends JpaRepository<EmailMetadata, Long> {

    Optional<EmailMetadata> findByMessageId(String messageId);

    List<EmailMetadata> findByAccountId(Long accountId);

    boolean existsByMessageId(String messageId);

    // Batch check for existing message IDs (for deduplication)
    @Query("SELECT e.messageId FROM EmailMetadata e WHERE e.messageId IN :messageIds")
    List<String> findExistingMessageIds(@Param("messageIds") List<String> messageIds);

    // Most frequent senders (excluding emails from me)
    @Query("SELECT e.senderEmail, e.senderName, COUNT(e) as cnt " +
           "FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.isFromMe = false " +
           "GROUP BY e.senderEmail, e.senderName " +
           "ORDER BY cnt DESC")
    List<Object[]> findTopSenders(@Param("accountIds") List<Long> accountIds);

    // Most frequent senders with date range
    @Query("SELECT e.senderEmail, e.senderName, COUNT(e) as cnt " +
           "FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.isFromMe = false " +
           "AND e.receivedAt >= :since " +
           "GROUP BY e.senderEmail, e.senderName " +
           "ORDER BY cnt DESC")
    List<Object[]> findTopSendersSince(@Param("accountIds") List<Long> accountIds,
                                        @Param("since") LocalDateTime since);

    // Senders ranked by unread emails
    @Query("SELECT e.senderEmail, e.senderName, COUNT(e) as cnt " +
           "FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.isFromMe = false AND e.isRead = false " +
           "GROUP BY e.senderEmail, e.senderName " +
           "ORDER BY cnt DESC")
    List<Object[]> findSendersByUnreadCount(@Param("accountIds") List<Long> accountIds);

    // Count emails received from each sender
    @Query("SELECT e.senderEmail, COUNT(e) as received " +
           "FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.isFromMe = false " +
           "GROUP BY e.senderEmail")
    List<Object[]> countEmailsReceivedBySender(@Param("accountIds") List<Long> accountIds);

    // Count replies sent to each recipient (emails from me that are replies)
    @Query("SELECT e.recipientEmail, COUNT(e) as sent " +
           "FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.isFromMe = true AND e.inReplyTo IS NOT NULL " +
           "GROUP BY e.recipientEmail")
    List<Object[]> countRepliesSentToRecipient(@Param("accountIds") List<Long> accountIds);

    // Count all emails sent to each recipient (for reply-to ranking)
    @Query("SELECT e.recipientEmail, COUNT(e) as sent " +
           "FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.isFromMe = true " +
           "GROUP BY e.recipientEmail")
    List<Object[]> countEmailsSentToRecipient(@Param("accountIds") List<Long> accountIds);

    // Get thread IDs for emails from a specific sender
    @Query("SELECT DISTINCT e.threadId FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.senderEmail = :senderEmail AND e.isFromMe = false")
    List<String> findThreadIdsBySender(@Param("accountIds") List<Long> accountIds,
                                        @Param("senderEmail") String senderEmail);

    // Check if user replied to any of these threads
    @Query("SELECT COUNT(e) FROM EmailMetadata e " +
           "WHERE e.accountId IN :accountIds AND e.isFromMe = true AND e.threadId IN :threadIds")
    Long countRepliesInThreads(@Param("accountIds") List<Long> accountIds,
                               @Param("threadIds") List<String> threadIds);

    // Get latest sync time for an account
    @Query("SELECT MAX(e.receivedAt) FROM EmailMetadata e WHERE e.accountId = :accountId")
    Optional<LocalDateTime> findLatestEmailDate(@Param("accountId") Long accountId);

    // Summary stats
    @Query("SELECT COUNT(e) FROM EmailMetadata e WHERE e.accountId IN :accountIds")
    Long countTotalEmails(@Param("accountIds") List<Long> accountIds);

    @Query("SELECT COUNT(e) FROM EmailMetadata e WHERE e.accountId IN :accountIds AND e.isRead = false")
    Long countUnreadEmails(@Param("accountIds") List<Long> accountIds);

    @Query("SELECT COUNT(DISTINCT e.senderEmail) FROM EmailMetadata e WHERE e.accountId IN :accountIds AND e.isFromMe = false")
    Long countUniqueSenders(@Param("accountIds") List<Long> accountIds);

    // Count emails by sender and account
    @Query("SELECT e.accountId, COUNT(e) FROM EmailMetadata e WHERE e.senderEmail = :senderEmail GROUP BY e.accountId")
    List<Object[]> countBySenderAndAccount(@Param("senderEmail") String senderEmail);
}
