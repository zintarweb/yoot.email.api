package com.emailutilities.repository;

import com.emailutilities.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RuleRepository extends JpaRepository<Rule, Long> {
    List<Rule> findByUserIdOrderByPriorityAsc(Long userId);
    List<Rule> findByUserIdAndEnabledOrderByPriorityAsc(Long userId, Boolean enabled);
}
