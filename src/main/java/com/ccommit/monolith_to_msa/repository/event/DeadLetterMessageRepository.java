package com.ccommit.monolith_to_msa.repository.event;

import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage;
import com.ccommit.monolith_to_msa.domain.event.DeadLetterMessage.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Dead Letter Queue Repository
 */
@Repository
public interface DeadLetterMessageRepository extends JpaRepository<DeadLetterMessage, Long> {
    
    List<DeadLetterMessage> findByStatus(MessageStatus status);
    
    List<DeadLetterMessage> findByStatusAndRetryCountLessThan(MessageStatus status, Integer maxRetryCount);
    
    List<DeadLetterMessage> findByChannel(String channel);
}
