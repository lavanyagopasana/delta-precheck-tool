package com.cloudfuze.deltatracker.config;

import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckItemEvidence;
import com.cloudfuze.deltatracker.repository.PreCheckItemEvidenceRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Copies each pre-check item's single {@code evidence_file_path} into the new
 * {@code precheck_item_evidence} table, once, on startup.
 *
 * <p>Needed for the same reason {@code TeamMembershipBackfill} is: {@code ddl-auto=update} creates
 * the new table but never moves a row into it, so on a database that already holds filled-in
 * pre-checks every attached file would be invisible to anything reading the new list -- while still
 * sitting in a column the item itself uses.
 *
 * <p>Idempotent: an item is skipped once it has any evidence row, so re-boots do nothing and an
 * engineer's later edits are never overwritten. Deliberately does NOT clear the old column -- it
 * stays as the first file, which is what keeps the submit precondition and the history snapshot
 * working.
 */
@Component
@Order(6)
public class PreCheckEvidenceBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PreCheckEvidenceBackfill.class);

    private final PreCheckItemRepository itemRepository;
    private final PreCheckItemEvidenceRepository evidenceRepository;

    public PreCheckEvidenceBackfill(PreCheckItemRepository itemRepository,
                                     PreCheckItemEvidenceRepository evidenceRepository) {
        this.itemRepository = itemRepository;
        this.evidenceRepository = evidenceRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        List<PreCheckItem> items = itemRepository.findAll();
        if (items.isEmpty()) {
            return;
        }
        // One query for every existing row instead of one per item: on a long-lived database this
        // runs against the whole checklist history at boot.
        Set<Long> alreadyMigrated = new HashSet<>();
        for (PreCheckItemEvidence existing : evidenceRepository.findAll()) {
            alreadyMigrated.add(existing.getItemId());
        }

        List<PreCheckItemEvidence> created = new ArrayList<>();
        for (PreCheckItem item : items) {
            if (!StringUtils.hasText(item.getEvidenceFilePath()) || alreadyMigrated.contains(item.getId())) {
                continue;
            }
            created.add(new PreCheckItemEvidence(item, item.getEvidenceFilePath(),
                    item.getEvidenceFileName(), item.getLastModifiedBy()));
        }
        if (created.isEmpty()) {
            return;
        }
        evidenceRepository.saveAll(created);
        log.info("Pre-check evidence backfill: moved {} single-file attachment(s) into "
                + "precheck_item_evidence. Items can now carry several files each.", created.size());
    }
}
