package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.ProductType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-product-type pre-check checklists. Email's list was confirmed with the team on 2026-08-06;
 * Content keeps the original eight items and Message is still a placeholder reusing Content's.
 *
 * <p>These are cheap assertions on a constant, but the list drives what gets seeded into the database
 * for every new combination and what order the form renders in -- a silent edit here changes what
 * engineers are required to evidence, which is worth pinning down.
 */
class ProductTypeChecklistTest {

    @Test
    void emailChecklistIsTheFourConfirmedItemsInOrder() {
        assertThat(ServerService.preCheckItemsFor(ProductType.EMAIL)).containsExactly(
                "Delta Type",
                "OneTime Migration",
                "Data Verified",
                "Workspace Status Updated in DB");
    }

    @Test
    void emailDropsTheContentOnlyItems() {
        // An email migration has no folder permissions, no hyperlinks and no local drive to reconcile,
        // and no Previous Delta Migration item -- requiring evidence for any of them would be unfillable.
        assertThat(ServerService.preCheckItemsFor(ProductType.EMAIL))
                .doesNotContain("Permissions Verified", "Hyperlinks Verified", "Drive changes",
                        ServerService.PRE_DELTA_MIGRATION_ITEM);
    }

    @Test
    void deltaTypeLeadsEveryChecklist() {
        // It decides whether the cycle is a pre-delta or the final one, so it has to be answered first
        // on every product type.
        for (ProductType type : ProductType.values()) {
            assertThat(ServerService.preCheckItemsFor(type)).startsWith(ServerService.DELTA_TYPE_ITEM);
        }
    }

    @Test
    void contentChecklistIsUnchanged() {
        assertThat(ServerService.preCheckItemsFor(ProductType.CONTENT)).containsExactly(
                "Delta Type",
                "OneTime Migration",
                "Previous Delta Migration",
                "Data Verified",
                "Permissions Verified",
                "Hyperlinks Verified",
                "Workspace Status Updated in DB",
                "Drive changes");
    }

    @Test
    void aNullProductTypeFallsBackToContentRatherThanThrowing() {
        // Servers created before product type existed still have null -- Map.of().getOrDefault throws on
        // a null key, so this path has to be handled before the lookup.
        assertThat(ServerService.preCheckItemsFor(null)).isEqualTo(ServerService.PRE_CHECK_ITEMS);
    }

    @Test
    void noChecklistIsEmpty() {
        // An empty checklist can never be submitted (submit requires at least one item), which would
        // silently lock every combination of that product type out of ever completing a pre-check.
        for (ProductType type : ProductType.values()) {
            List<String> items = ServerService.preCheckItemsFor(type);
            assertThat(items).as("checklist for %s", type).isNotEmpty();
        }
    }
}
