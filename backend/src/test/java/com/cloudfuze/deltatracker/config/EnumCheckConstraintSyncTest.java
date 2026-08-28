package com.cloudfuze.deltatracker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EnumCheckConstraintSync} -- the repair for stale Hibernate-generated enum CHECK constraints.
 *
 * <p>The behaviour that matters most is what it does NOT do: it must not fail a boot, must not touch a
 * non-Postgres database, and must not rewrite a constraint that is already correct. A repair that
 * stops the app from starting is worse than the bug it fixes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"}) // JdbcTemplate.execute(ConnectionCallback) is generic
class EnumCheckConstraintSyncTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private EnumCheckConstraintSync sync;

    private void enabled(boolean value) {
        ReflectionTestUtils.setField(sync, "enabled", value);
    }

    private void databaseProduct(String product) {
        when(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
                .thenReturn(product);
    }

    /**
     * Answers the constraint lookup only for {@code table}, and returns nothing for the other 15
     * registered columns. A blanket stub would hand ItemStatus's definition to app_users.role, which
     * then looks stale and gets rebuilt -- drowning the assertion in unrelated DDL.
     */
    private void constraintLookupFor(String table, String name, String definition) {
        when(jdbcTemplate.queryForList(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("'" + table + "'")
                    ? List.of(Map.of("name", name, "definition", definition))
                    : List.of();
        });
    }

    /**
     * Records the DDL passed to {@code execute(String)}. Deliberately a doAnswer rather than an
     * ArgumentCaptor: {@code JdbcTemplate.execute} is overloaded, and a captor also matches the
     * {@code execute(ConnectionCallback)} call used to identify the database.
     */
    private List<String> recordExecutedDdl() {
        List<String> executed = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            executed.add(inv.getArgument(0));
            return null;
        }).when(jdbcTemplate).execute(anyString());
        return executed;
    }

    /** One stale constraint row as pg_catalog would return it: missing the two newest ItemStatus values. */
    private List<Map<String, Object>> staleItemStatusConstraint() {
        return List.of(Map.of(
                "name", "precheck_items_status_check",
                "definition", "CHECK (((status)::text = ANY (ARRAY['NOT_STARTED'::text, 'IN_PROGRESS'::text,"
                        + " 'CONFLICTS'::text, 'COMPLETED'::text, 'NOT_AVAILABLE'::text,"
                        + " 'PARTIALLY_COMPLETED'::text, 'ENABLED'::text, 'NOT_ENABLED'::text,"
                        + " 'PRE_DELTA'::text, 'FINAL_DELTA'::text])))"));
    }

    // --- the registry -----------------------------------------------------------------------------

    @Test
    void everyRegisteredColumnNamesATableAndAColumnAndANonEmptyEnum() {
        assertThat(EnumCheckConstraintSync.ENUM_COLUMNS).isNotEmpty();
        EnumCheckConstraintSync.ENUM_COLUMNS.forEach((key, enumType) -> {
            assertThat(key).as("registry key must be table.column").matches("^[a-z_]+\\.[a-z_]+$");
            assertThat(enumType.getEnumConstants())
                    .as("%s maps to an enum with no constants", key)
                    .isNotEmpty();
        });
    }

    @Test
    void coversTheColumnThatCausedTheProductionFailure() {
        // precheck_items.status is the one that produced "That conflicts with an existing record" on the
        // Hyperlinks Verified and Drive changes items. delta_cycle_items.status snapshots the same enum
        // on a decline rollover, so it would have failed next.
        assertThat(EnumCheckConstraintSync.ENUM_COLUMNS).containsKeys(
                "precheck_items.status", "delta_cycle_items.status");
    }

    // --- when it must do nothing ------------------------------------------------------------------

    @Test
    void doesNothingWhenDisabled() {
        enabled(false);

        sync.syncConstraints();

        verify(jdbcTemplate, never()).queryForList(anyString());
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void doesNothingOnANonPostgresDatabase() {
        // The H2 test profile must be left completely alone -- the SQL below is pg_catalog-specific.
        enabled(true);
        databaseProduct("H2");

        sync.syncConstraints();

        verify(jdbcTemplate, never()).queryForList(anyString());
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void leavesAConstraintAloneWhenItAlreadyListsEveryValue() {
        enabled(true);
        databaseProduct("PostgreSQL");
        // Every current value present -> nothing to do. Built from the enum so this test cannot go
        // stale when a value is added.
        String all = Arrays.stream(com.cloudfuze.deltatracker.entity.ItemStatus.values())
                .map(v -> "'" + v.name() + "'::text")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        constraintLookupFor("precheck_items", "precheck_items_status_check",
                "CHECK (status = ANY (ARRAY[" + all + "])))");

        sync.syncConstraints();

        // No ALTER TABLE at all: a normal boot on a current database writes nothing.
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void doesNothingWhenTheTableHasNoSuchConstraint() {
        // Fresh database where Hibernate hasn't created the table yet, or never generated a constraint.
        enabled(true);
        databaseProduct("PostgreSQL");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        sync.syncConstraints();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    // --- when it must repair ----------------------------------------------------------------------

    @Test
    void rebuildsAStaleConstraintWithEveryCurrentValue() {
        enabled(true);
        databaseProduct("PostgreSQL");
        constraintLookupFor("precheck_items", "precheck_items_status_check",
                staleItemStatusConstraint().get(0).get("definition").toString());

        List<String> executed = recordExecutedDdl();

        sync.syncConstraints();

        String added = executed.stream()
                .filter(s -> s.contains("ADD CONSTRAINT"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ADD CONSTRAINT was issued; executed=" + executed));

        // The two values whose absence broke production.
        assertThat(added).contains("'NOT_APPLICABLE'").contains("'UP_TO_DATE'");
        // And it must still permit everything already stored, or existing rows would become invalid.
        for (com.cloudfuze.deltatracker.entity.ItemStatus value : com.cloudfuze.deltatracker.entity.ItemStatus.values()) {
            assertThat(added).contains("'" + value.name() + "'");
        }
        assertThat(executed).anySatisfy(s -> assertThat(s).contains("DROP CONSTRAINT"));
    }

    @Test
    void dropsBeforeAddingSoTheRebuildCannotCollide() {
        enabled(true);
        databaseProduct("PostgreSQL");
        constraintLookupFor("precheck_items", "precheck_items_status_check",
                staleItemStatusConstraint().get(0).get("definition").toString());

        List<String> executed = recordExecutedDdl();

        sync.syncConstraints();

        List<String> statements = executed;
        int firstDrop = -1;
        int firstAdd = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (firstDrop < 0 && statements.get(i).contains("DROP CONSTRAINT")) firstDrop = i;
            if (firstAdd < 0 && statements.get(i).contains("ADD CONSTRAINT")) firstAdd = i;
        }
        assertThat(firstDrop).isGreaterThanOrEqualTo(0);
        assertThat(firstAdd).isGreaterThan(firstDrop);
    }

    // --- it must never take the application down --------------------------------------------------

    @Test
    void survivesAFailureOnOneColumnAndKeepsGoing() {
        enabled(true);
        databaseProduct("PostgreSQL");
        when(jdbcTemplate.queryForList(anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("boom"));

        // Not rethrown: the app must start even if the repair cannot run.
        assertThatCode(() -> sync.syncConstraints()).doesNotThrowAnyException();
    }

    @Test
    void survivesAFailedAlterStatement() {
        enabled(true);
        databaseProduct("PostgreSQL");
        constraintLookupFor("precheck_items", "precheck_items_status_check",
                staleItemStatusConstraint().get(0).get("definition").toString());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("denied"))
                .when(jdbcTemplate).execute(anyString());

        assertThatCode(() -> sync.syncConstraints()).doesNotThrowAnyException();
    }

    @Test
    void survivesNotBeingAbleToIdentifyTheDatabase() {
        enabled(true);
        when(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("no connection"));

        assertThatCode(() -> sync.syncConstraints()).doesNotThrowAnyException();
        verify(jdbcTemplate, never()).execute(anyString());
    }
}
