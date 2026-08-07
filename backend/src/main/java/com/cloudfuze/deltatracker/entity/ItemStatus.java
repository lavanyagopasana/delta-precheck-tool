package com.cloudfuze.deltatracker.entity;

/**
 * The answer an engineer gives to one pre-check item.
 *
 * <p>Not every value applies to every item -- the options shown are scoped per item and per product
 * type on the frontend (see statusOptionsFor in PreCheckPanel). This enum is the union of every
 * answer any item can carry, because they all persist to one {@code status} column.
 *
 * <p>NOT_STARTED is the only value that blocks submission (PreCheckSubmissionService.isItemComplete).
 * Everything else counts as answered, including the deliberately negative ones: NOT_AVAILABLE and
 * NOT_ENABLED are real answers about the migration, not unfinished work.
 */
public enum ItemStatus {
    NOT_STARTED,
    IN_PROGRESS,
    CONFLICTS,
    COMPLETED,
    // "Not available" -- Content's Previous Delta Migration only.
    NOT_AVAILABLE,
    // Message's OneTime Migration: a migration that moved some but not all of the history.
    PARTIALLY_COMPLETED,
    // Message's Delta Message Sync, which is a yes/no capability rather than a progress state.
    ENABLED,
    NOT_ENABLED,
    // The Delta Type item's answer, which settles whether the cycle is a pre-delta or the final one.
    PRE_DELTA,
    FINAL_DELTA
}
