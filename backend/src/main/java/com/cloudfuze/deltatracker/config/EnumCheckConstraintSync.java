package com.cloudfuze.deltatracker.config;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Brings every enum-backed column's Postgres {@code CHECK} constraint back in line with its Java enum
 * at startup.
 *
 * <h2>The bug this exists to fix</h2>
 *
 * Hibernate 6 (Spring Boot 3.x) generates a {@code CHECK} constraint for each
 * {@code @Enumerated(EnumType.STRING)} column, listing that enum's values:
 *
 * <pre>{@code precheck_items_status_check CHECK (status IN ('NOT_STARTED', ..., 'FINAL_DELTA'))}</pre>
 *
 * <p>{@code ddl-auto=update} adds tables and columns but <b>never alters an existing constraint</b>.
 * So adding a value to an enum is a schema change that silently does not get applied. It works on
 * every freshly created database -- which is why it passes locally and in tests -- and fails only on a
 * long-lived one, i.e. production.
 *
 * <p>That is not hypothetical. Commit {@code ba0bf01} added {@code NOT_APPLICABLE} and
 * {@code UP_TO_DATE} to {@link ItemStatus}. On the deployed database, whose {@code precheck_items}
 * table predates that commit, choosing either value on the Hyperlinks Verified or Drive changes item
 * made Postgres reject the UPDATE. Spring wrapped it as {@code DataIntegrityViolationException} and
 * the user saw "That conflicts with an existing record -- please check your input and try again." --
 * a message that says nothing about the real cause. Every other item on the same form saved fine.
 *
 * <h2>Why a startup task rather than a one-off migration</h2>
 *
 * This project has no migration tool ({@code ddl-auto=update}, no Flyway/Liquibase), so there is no
 * migration file to add. Running the SQL by hand fixes today's two values and leaves the trap armed
 * for the next enum value somebody adds. Deriving the value list from the enum class by reflection
 * makes this self-maintaining: any future addition is applied on the next deploy, by the same code,
 * with no action from anybody.
 *
 * <h2>Safety</h2>
 *
 * <ul>
 *   <li><b>Postgres only.</b> Skipped on any other database, so the H2 test profile is untouched.</li>
 *   <li><b>Idempotent.</b> A constraint that already lists every value is left alone and logged at
 *       debug, so a normal boot is silent.</li>
 *   <li><b>Never fails the boot.</b> Every failure is logged and skipped. A schema repair that stops
 *       the app from starting would be worse than the bug it fixes.</li>
 *   <li><b>Widening only.</b> Constraints are rebuilt from the current enum, which is a superset of
 *       what any stored row can contain -- rows were written through the same enum. So no existing
 *       row can violate the new constraint.</li>
 *   <li>Set {@code app.enum-constraint-sync-enabled=false} to switch it off.</li>
 * </ul>
 */
@Component
public class EnumCheckConstraintSync {

    private static final Logger log = LoggerFactory.getLogger(EnumCheckConstraintSync.class);

    /**
     * Every {@code @Enumerated} column in the schema, as {@code table.column -> enum}.
     *
     * <p>Deliberately an explicit list rather than a reflection sweep of the entity package: a column
     * this misses is silently left broken, and silence is the exact failure mode being fixed here. An
     * explicit list is reviewable, and {@code EnumCheckConstraintSyncTest} fails if an entity gains an
     * enum column that nobody registered, so the list cannot quietly fall behind.
     */
    static final Map<String, Class<? extends Enum<?>>> ENUM_COLUMNS = buildRegistry();

    private static Map<String, Class<? extends Enum<?>>> buildRegistry() {
        Map<String, Class<? extends Enum<?>>> m = new LinkedHashMap<>();
        m.put("app_users.role", AppUserRole.class);
        m.put("delta_cycles.delta_type", DeltaType.class);
        m.put("delta_cycles.status", DeltaCycleStatus.class);
        m.put("delta_cycles.declined_by_role", SignOffRole.class);
        m.put("delta_cycle_items.status", ItemStatus.class);
        m.put("delta_cycle_signoffs.role", SignOffRole.class);
        m.put("delta_cycle_signoffs.status", SignOffStatus.class);
        m.put("precheck_items.status", ItemStatus.class);
        m.put("precheck_submissions.status", SubmissionStatus.class);
        m.put("servers.product_type", ProductType.class);
        m.put("servers.status", PairStatus.class);
        m.put("sign_offs.role", SignOffRole.class);
        m.put("sign_offs.status", SignOffStatus.class);
        m.put("tickets.status", TicketStatus.class);
        m.put("workspace_combinations.status", PairStatus.class);
        m.put("workspace_combinations.current_delta_type", DeltaType.class);
        return m;
    }

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.enum-constraint-sync-enabled:true}")
    private boolean enabled;

    public EnumCheckConstraintSync(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs after the context is up, which is after Hibernate's own DDL has run -- on a brand new
     * database the tables and their constraints exist by now, so this sees the real current state.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncConstraints() {
        if (!enabled) {
            log.info("Enum CHECK constraint sync disabled (app.enum-constraint-sync-enabled=false).");
            return;
        }
        if (!isPostgres()) {
            log.debug("Enum CHECK constraint sync skipped -- not PostgreSQL.");
            return;
        }

        List<String> repaired = new ArrayList<>();
        for (Map.Entry<String, Class<? extends Enum<?>>> entry : ENUM_COLUMNS.entrySet()) {
            String[] parts = entry.getKey().split("\\.", 2);
            try {
                if (syncOne(parts[0], parts[1], entry.getValue())) {
                    repaired.add(entry.getKey());
                }
            } catch (RuntimeException e) {
                // Logged and skipped, never rethrown: see the class comment on never failing the boot.
                log.warn("Could not sync the CHECK constraint on {} -- leaving it as it is: {}",
                        entry.getKey(), e.toString());
            }
        }

        if (repaired.isEmpty()) {
            log.info("Enum CHECK constraints are already current ({} columns checked).", ENUM_COLUMNS.size());
        } else {
            // Logged at INFO with the column names, because this rewrites production schema. A silent
            // schema change is not something anybody should have to discover from behaviour.
            log.info("Enum CHECK constraints widened to match the current enums: {}. "
                            + "These were stale because ddl-auto=update never alters an existing constraint.",
                    repaired);
        }
    }

    private boolean isPostgres() {
        try {
            String product = jdbcTemplate.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                            connection.getMetaData().getDatabaseProductName());
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (RuntimeException e) {
            log.warn("Could not determine the database product -- skipping enum constraint sync: {}", e.toString());
            return false;
        }
    }

    /** @return true when the constraint was actually rebuilt. */
    private boolean syncOne(String table, String column, Class<? extends Enum<?>> enumType) {
        List<String> values = Arrays.stream(enumType.getEnumConstants())
                .map(Enum::name)
                .toList();

        // pg_catalog rather than information_schema: pg_get_constraintdef gives the constraint's real
        // current definition, which is what has to be compared against the enum.
        //
        // table and column are interpolated rather than bound. They come from ENUM_COLUMNS -- compile
        // time string constants in this file, never user input, never request data -- so there is
        // nothing here an attacker can reach. Binding them instead would mean the varargs overload of
        // queryForList, which is ambiguous to both javac (against queryForList(String, Class, Object...))
        // and to Mockito's vararg matching.
        String sql = """
                SELECT con.conname AS name, pg_get_constraintdef(con.oid) AS definition
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                WHERE con.contype = 'c'
                  AND nsp.nspname = current_schema()
                  AND rel.relname = '%s'
                  AND pg_get_constraintdef(con.oid) LIKE '%%%s%%'
                """.formatted(table, column);
        List<Map<String, Object>> found = jdbcTemplate.queryForList(sql);

        if (found.isEmpty()) {
            // No constraint on this column: either the table doesn't exist yet, or Hibernate never
            // generated one. Both are fine -- nothing can reject a valid enum value.
            return false;
        }

        boolean changed = false;
        for (Map<String, Object> row : found) {
            String name = String.valueOf(row.get("name"));
            String definition = String.valueOf(row.get("definition"));
            if (values.stream().allMatch(v -> definition.contains("'" + v + "'"))) {
                log.debug("{}.{} constraint {} already lists all {} values.", table, column, name, values.size());
                continue;
            }
            List<String> missing = values.stream().filter(v -> !definition.contains("'" + v + "'")).toList();
            String inList = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
            // Quoted identifiers, and the value list comes from Java enum constant names (never user
            // input), so there is nothing here an attacker could reach.
            jdbcTemplate.execute("ALTER TABLE \"" + table + "\" DROP CONSTRAINT \"" + name + "\"");
            jdbcTemplate.execute("ALTER TABLE \"" + table + "\" ADD CONSTRAINT \"" + name
                    + "\" CHECK (\"" + column + "\" IN (" + inList + "))");
            log.info("Rebuilt {} on {}.{} -- it was missing {}.", name, table, column, missing);
            changed = true;
        }
        return changed;
    }
}
