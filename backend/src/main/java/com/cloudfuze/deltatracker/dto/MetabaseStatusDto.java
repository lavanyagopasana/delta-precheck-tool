package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * One product type's migration status for a project, as read out of its Metabase database.
 *
 * <p>A project returns a list of these -- one per product type its servers use. Every field that could
 * be absent is modelled explicitly rather than defaulted, because the failure modes here all look like
 * "zero work done" if collapsed into numbers:
 * <ul>
 *   <li>{@code databaseName == null} -- nobody has fixed a database for this product type yet.</li>
 *   <li>{@code error != null} -- Metabase was configured but the read failed (unreachable, rejected
 *       credential, unknown database name). Deliberately NOT reported as zero counts.</li>
 *   <li>{@code statuses} empty with no error -- the collection genuinely has no customer-owned rows.</li>
 * </ul>
 */
@Getter
@Setter
public class MetabaseStatusDto {

    private String productType;
    private String databaseName;
    // The collection the counts came from (MessageWorkSpace / MoveWorkSpaces / emailWorkSpace). Shown
    // so a surprising number can be traced back to what was actually queried.
    private String collection;
    // Non-null when this product type could not be read. The panel shows it instead of counts.
    private String error;
    // processStatus -> workspace count, in the order the service chose to present them.
    private List<MetabaseStatusCountDto> statuses;
    private long totalWorkspaces;
    // The non-CloudFuze owner emails whose rows were counted. Surfaced because the query excludes the
    // @cloudfuze.com domain (internal test runs), and "which owners did this include?" is otherwise
    // invisible -- a database can carry several customer owners on different domains.
    private List<String> ownerEmails;
    // How many rows were excluded as CloudFuze-internal. Not hidden: on one real database this was 53
    // workspaces carrying 47 conflicts, which would have inflated the customer's figures.
    private long excludedInternalWorkspaces;

    // ---- Drive changes. CONTENT only; null on the email and message entries, because
    // DriveChangeIdDetails is a Drive concept and that collection does not exist for them. A null
    // list and an empty list mean different things here: null is "not applicable to this product
    // type", empty is "asked, and the customer has no Drive changes".
    private List<MetabaseStatusCountDto> driveChanges;
    // Whose changes these are: Users._id plus the primaryEmail it belongs to. Carried so the panel
    // can name the customer AND give the id needed to reproduce the figure in Metabase by hand.
    private List<MetabaseUserDto> driveChangeUsers;
    private long totalDriveChanges;
    // Non-null when the Drive change read specifically failed. Kept separate from `error` so a
    // failure here still leaves the workspace counts on screen rather than blanking the whole block.
    private String driveChangesError;
}
