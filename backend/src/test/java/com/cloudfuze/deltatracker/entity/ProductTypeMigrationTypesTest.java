package com.cloudfuze.deltatracker.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a product type out of PMO's {@code migrationTypes} string.
 *
 * <p>This is what lets a freshly synced project have its Metabase database chosen during setup instead
 * of being blocked behind creating a server first. Every token below is taken from the live PMO feed
 * (all 190 projects, 2026-08-27), with the real occurrence counts noted, so the residual-CONTENT rule
 * is grounded in what PMO actually sends rather than a guess.
 */
class ProductTypeMigrationTypesTest {

    @Test
    void readsTheEmailPlatforms() {
        // Gmail (43) and Outlook (33) are the only email tokens in the live feed.
        assertThat(ProductType.fromMigrationTypes("Gmail - Gmail, Outlook - Gmail"))
                .containsExactly(ProductType.EMAIL);
        assertThat(ProductType.fromMigrationTypes("Outlook - Gmail"))
                .containsExactly(ProductType.EMAIL);
    }

    @Test
    void readsTheMessagePlatforms() {
        // Teams (56), Slack (47), Chat (20), Meta (2), Viva (1).
        assertThat(ProductType.fromMigrationTypes("Slack - Teams")).containsExactly(ProductType.MESSAGE);
        assertThat(ProductType.fromMigrationTypes("Slack - Chat")).containsExactly(ProductType.MESSAGE);
        assertThat(ProductType.fromMigrationTypes("Chat - Chat")).containsExactly(ProductType.MESSAGE);
        assertThat(ProductType.fromMigrationTypes("Slack - Slack")).containsExactly(ProductType.MESSAGE);
    }

    @Test
    void readsMetaAndVivaAsMessageNotContent() {
        // Meta Workplace and Viva Engage are messaging platforms. Treating "Meta" as content is what
        // made "mercado libre" (migrationTypes "Meta - Chat") look like the one PMO project spanning
        // two product types -- no PMO project actually does.
        assertThat(ProductType.fromMigrationTypes("Meta - Chat")).containsExactly(ProductType.MESSAGE);
        assertThat(ProductType.fromMigrationTypes("Viva - Teams")).containsExactly(ProductType.MESSAGE);
    }

    @Test
    void readsTheStoragePlatformsAsContent() {
        // MyDrive (72), SharePoint (64), Shared Drive (50), OneDrive (49), Dropbox (29), Box (16),
        // Egnyte (7), ShareFile (3), NFS (3), Citrix (1).
        assertThat(ProductType.fromMigrationTypes("OneDrive - MyDrive, SharePoint - Shared Drive"))
                .containsExactly(ProductType.CONTENT);
        assertThat(ProductType.fromMigrationTypes("Box - MyDrive, Box - Shared Drive"))
                .containsExactly(ProductType.CONTENT);
        assertThat(ProductType.fromMigrationTypes("Dropbox - SharePoint")).containsExactly(ProductType.CONTENT);
        assertThat(ProductType.fromMigrationTypes("NFS - Egnyte")).containsExactly(ProductType.CONTENT);
    }

    @Test
    void handlesTheRealProjectsWhoseNameDoesNotShowItsType() {
        // These are live ACTIVE projects. Their names carry no "(...)" suffix -- that is only appended
        // to disambiguate duplicate names -- so they LOOK typeless on the projects list but are not.
        assertThat(ProductType.fromMigrationTypes("Shared Drive - SharePoint, OneDrive - MyDrive"))
                .containsExactly(ProductType.CONTENT); // cloudsoft
        assertThat(ProductType.fromMigrationTypes("MyDrive - MyDrive, Shared Drive - Shared Drive"))
                .containsExactly(ProductType.CONTENT); // centric building inc
        assertThat(ProductType.fromMigrationTypes("SharePoint - SharePoint"))
                .containsExactly(ProductType.CONTENT); // city of orange police department
    }

    @Test
    void treatsUnknownTokensAsContentBecauseThatIsPmosResidualClass() {
        // "Other" and "CONTENT" appear as literal tokens in the live feed, and PMO adds storage
        // platforms far more often than messaging ones -- so defaulting to CONTENT is the behaviour
        // that stays correct when a new platform appears rather than dropping the project's type.
        assertThat(ProductType.fromMigrationTypes("Other - MyDrive")).containsExactly(ProductType.CONTENT);
        assertThat(ProductType.fromMigrationTypes("CONTENT - CONTENT")).containsExactly(ProductType.CONTENT);
        assertThat(ProductType.fromMigrationTypes("SomeNewPlatform - MyDrive"))
                .containsExactly(ProductType.CONTENT);
    }

    @Test
    void isCaseAndWhitespaceInsensitive() {
        assertThat(ProductType.fromMigrationTypes("  gmail   -   OUTLOOK  "))
                .containsExactly(ProductType.EMAIL);
        assertThat(ProductType.fromMigrationTypes("SLACK-teams")).containsExactly(ProductType.MESSAGE);
    }

    @Test
    void returnsNothingForBlankOrMissingInput() {
        // A hand-created project has no PMO record, so this must be empty rather than defaulting to
        // CONTENT -- guessing a type would point the status panel at the wrong collection.
        assertThat(ProductType.fromMigrationTypes(null)).isEmpty();
        assertThat(ProductType.fromMigrationTypes("")).isEmpty();
        assertThat(ProductType.fromMigrationTypes("   ")).isEmpty();
        assertThat(ProductType.fromMigrationTypeToken(null)).isEmpty();
        assertThat(ProductType.fromMigrationTypeToken("  ")).isEmpty();
    }

    @Test
    void readsBothSidesOfAPair() {
        // A cross-product pair would otherwise depend on which side PMO listed first.
        assertThat(ProductType.fromMigrationTypes("Slack - MyDrive"))
                .containsExactlyInAnyOrder(ProductType.MESSAGE, ProductType.CONTENT);
    }
}
