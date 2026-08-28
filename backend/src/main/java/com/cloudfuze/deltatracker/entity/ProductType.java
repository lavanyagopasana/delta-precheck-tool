package com.cloudfuze.deltatracker.entity;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public enum ProductType {
    MESSAGE,
    EMAIL,
    CONTENT;

    // PMO's migrationTypes vocabulary, taken from all 190 live projects on 2026-08-27. Any token not
    // listed as message or email is content: that is the residual class in PMO's own data (MyDrive 72,
    // SharePoint 64, Shared Drive 50, OneDrive 49, Dropbox 29, Box 16, Egnyte 7, ShareFile 3, NFS 3,
    // Citrix 1, plus the literal tokens "Other" and "CONTENT"), so defaulting to CONTENT is the
    // behaviour that stays correct when PMO adds a new storage platform.
    //
    // "Meta" (Meta Workplace) and "Viva" (Viva Engage) are messaging platforms, not storage -- reading
    // them as content is what made one project look like it spanned two product types when no PMO
    // project ever does.
    private static final Set<String> EMAIL_TOKENS =
            Set.of("gmail", "outlook", "exchange", "imap", "office365 mail");
    private static final Set<String> MESSAGE_TOKENS =
            Set.of("slack", "teams", "microsoft teams", "google chat", "chat", "hangouts", "meta", "viva");

    /**
     * The product type a single PMO migration-type token names, e.g. {@code "Gmail" -> EMAIL},
     * {@code "MyDrive" -> CONTENT}. Empty only for a blank token.
     */
    public static Optional<ProductType> fromMigrationTypeToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (EMAIL_TOKENS.contains(normalized)) {
            return Optional.of(EMAIL);
        }
        if (MESSAGE_TOKENS.contains(normalized)) {
            return Optional.of(MESSAGE);
        }
        return Optional.of(CONTENT);
    }

    /**
     * Every product type named by a PMO {@code migrationTypes} string, e.g.
     * {@code "Gmail - Gmail, Outlook - Gmail" -> [EMAIL]} and
     * {@code "OneDrive - MyDrive, Shared Drive - SharePoint" -> [CONTENT]}.
     *
     * <p>The format is comma-separated "Source - Destination" pairs. Both sides are read, because a
     * cross-platform pair still names the same product type on each side and reading only one would
     * make the result depend on which side PMO happened to list first.
     *
     * <p>Insertion-ordered so a caller that renders these gets a stable order.
     */
    public static Set<ProductType> fromMigrationTypes(String migrationTypes) {
        Set<ProductType> found = new LinkedHashSet<>();
        if (migrationTypes == null || migrationTypes.isBlank()) {
            return found;
        }
        for (String pair : migrationTypes.split(",")) {
            for (String side : pair.split("-")) {
                fromMigrationTypeToken(side).ifPresent(found::add);
            }
        }
        return found;
    }
}
