package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.PreCheckItemEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreCheckItemEvidenceRepository extends JpaRepository<PreCheckItemEvidence, Long> {

    // Upload order is the order an approver sees. Id breaks ties, because several files chosen in one
    // go are written inside the same millisecond and would otherwise come back shuffled.
    List<PreCheckItemEvidence> findByItemIdOrderByUploadedAtAscIdAsc(Long itemId);

    // One query for a whole checklist rather than one per item.
    List<PreCheckItemEvidence> findByItemIdInOrderByUploadedAtAscIdAsc(List<Long> itemIds);

    void deleteByItemId(Long itemId);
}
