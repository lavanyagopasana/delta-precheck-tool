package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.PreCheckItemEdit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreCheckItemEditRepository extends JpaRepository<PreCheckItemEdit, Long> {

    // Newest first: the trail is read to answer "who touched this last", and an item edited twenty
    // times should not need scrolling to find that.
    List<PreCheckItemEdit> findByItemIdOrderByEditedAtDescIdDesc(Long itemId);

    // One query for a whole checklist.
    List<PreCheckItemEdit> findByItemIdInOrderByEditedAtDescIdDesc(List<Long> itemIds);
}
