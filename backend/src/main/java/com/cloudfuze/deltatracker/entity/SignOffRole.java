package com.cloudfuze.deltatracker.entity;

import java.util.List;

public enum SignOffRole {
    MIGRATION_LEAD,
    DEV_LEAD,
    QA_LEAD;

    // The fixed approval order: Migration Manager -> Dev Lead -> QA Lead. Canonical home so the
    // sequence isn't duplicated across services (was in both SignOffService and ProjectService).
    public static final List<SignOffRole> APPROVAL_SEQUENCE = List.of(MIGRATION_LEAD, DEV_LEAD, QA_LEAD);

    // Human-readable label (previously duplicated as a private switch in both services).
    public String label() {
        return switch (this) {
            case MIGRATION_LEAD -> "Migration Manager";
            case DEV_LEAD -> "Dev Lead";
            case QA_LEAD -> "QA Lead";
        };
    }
}
