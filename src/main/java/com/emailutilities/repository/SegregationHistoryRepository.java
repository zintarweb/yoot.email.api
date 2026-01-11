package com.emailutilities.repository;

import com.emailutilities.entity.SegregationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface SegregationHistoryRepository extends JpaRepository<SegregationHistory, Long> {

    List<SegregationHistory> findByUserId(Long userId);

    Optional<SegregationHistory> findByUserIdAndSenderEmail(Long userId, String senderEmail);

    @Query("SELECT s.senderEmail FROM SegregationHistory s WHERE s.userId = :userId")
    Set<String> findSegregatedSendersByUserId(@Param("userId") Long userId);

    @Query("SELECT s.senderEmail FROM SegregationHistory s WHERE s.userId = :userId AND s.operationType = :opType")
    Set<String> findSendersByUserIdAndType(@Param("userId") Long userId,
                                           @Param("opType") SegregationHistory.OperationType opType);

    boolean existsByUserIdAndSenderEmail(Long userId, String senderEmail);
}
