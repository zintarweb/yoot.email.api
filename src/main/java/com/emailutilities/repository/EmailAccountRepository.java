package com.emailutilities.repository;

import com.emailutilities.entity.EmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailAccountRepository extends JpaRepository<EmailAccount, Long> {
    List<EmailAccount> findByUserId(Long userId);
    List<EmailAccount> findByUserIdAndSyncStatus(Long userId, EmailAccount.SyncStatus status);
    Optional<EmailAccount> findByEmailAddressAndProvider(String emailAddress, EmailAccount.EmailProvider provider);
    Optional<EmailAccount> findByUserIdAndEmailAddressAndProvider(Long userId, String emailAddress, EmailAccount.EmailProvider provider);
}
