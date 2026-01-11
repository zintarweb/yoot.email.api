package com.emailutilities.repository;

import com.emailutilities.entity.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findByUserIdOrderByStartedAtDesc(Long userId);

    Optional<SyncJob> findFirstByUserIdAndStatusOrderByStartedAtDesc(Long userId, SyncJob.JobStatus status);

    List<SyncJob> findByStatus(SyncJob.JobStatus status);

    boolean existsByUserIdAndStatus(Long userId, SyncJob.JobStatus status);
}
