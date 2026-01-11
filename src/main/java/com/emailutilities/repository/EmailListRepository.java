package com.emailutilities.repository;

import com.emailutilities.entity.EmailList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface EmailListRepository extends JpaRepository<EmailList, Long> {
    List<EmailList> findByUserId(Long userId);
    List<EmailList> findByUserIdAndListType(Long userId, EmailList.ListType listType);
}
