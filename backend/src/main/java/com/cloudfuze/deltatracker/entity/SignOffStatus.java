package com.cloudfuze.deltatracker.entity;

public enum SignOffStatus {
    PENDING,
    APPROVED,
    DECLINED,
    // Only ever set on a QA Lead row, when the Dev Lead decides QA approval isn't needed for this server.
    SKIPPED
}
