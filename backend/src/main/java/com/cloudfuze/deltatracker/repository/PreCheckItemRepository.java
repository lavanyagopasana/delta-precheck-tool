package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.PreCheckItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreCheckItemRepository extends JpaRepository<PreCheckItem, Long> {

    List<PreCheckItem> findByServerId(Long serverId);
}
