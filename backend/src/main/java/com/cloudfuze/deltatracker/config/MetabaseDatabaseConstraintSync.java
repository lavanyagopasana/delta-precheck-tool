package com.cloudfuze.deltatracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drops the stale two-column unique constraint on {@code project_metabase_databases} so a project can
 * hold more than one Metabase database per product type.
 *
 * <h2>The bug this exists to fix</h2>
 *
 * The table was created with {@code UNIQUE (project_id, product_type)} -- one database per product
 * type, enforced by Postgres. Allowing several means the constraint has to span
 * {@code database_name} too.
 *
 * <p>{@code ddl-auto=update} adds tables, columns and new constraints but <b>never drops an existing
 * one</b>. Hibernate will happily create the new three-column constraint and leave the old
 * two-column one in place beside it, and the old one still rejects the second database. The failure
 * is the same shape as the enum-CHECK bug {@link EnumCheckConstraintSync} fixes: it works on every
 * freshly created database -- local, tests, CI -- and fails only on a long-lived one, i.e.
 * production, surfacing as {@code DataIntegrityViolationException} and the generic "That conflicts
 * with an existing record" message, which says nothing about the real cause.
 *
 * <h2>Safety</h2>
 *
 * Drops only a UNIQUE constraint on exactly {@code (project_id, product_type)} of this one table,
 * found by querying the catalog rather than by guessing its generated name (Hibernate names it
 * something like {@code uk_abc123...}, which differs per database). Anything else -- the primary
 * key, the foreign keys, the new three-column constraint -- is left untouched, because the column
 * list is matched exactly. Idempotent: on an already-migrated or brand-new database it finds nothing
 * and logs that it had nothing to do.
 *
 * <p>Failure is logged, never fatal. A database this cannot repair still starts and still serves
 * every existing project; only adding a second database for one product type fails, which is the
 * situation that already exists today.
 */
@Component
public class MetabaseDatabaseConstraintSync {

    private static final Logger log = LoggerFactory.getLogger(MetabaseDatabaseConstraintSync.class);

    private static final String TABLE = "project_metabase_databases";

    private final JdbcTemplate jdbcTemplate;

    public MetabaseDatabaseConstraintSync(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void dropStaleUniqueConstraint() {
        try {
            // Every UNIQUE constraint on the table whose column set is EXACTLY {project_id,
            // product_type}.
            //
            // Ordered by attname, NOT by attnum. attnum is physical column order, which is decided
            // by whatever order Hibernate happened to create the columns in -- on this database it
            // puts product_type before project_id, so an attnum-ordered comparison against
            // ARRAY['project_id','product_type'] silently matched nothing and the repair reported
            // "nothing to drop" while the stale constraint sat there. Sorting both sides by name
            // makes the comparison a set comparison, which is what was meant.
            List<String> stale = jdbcTemplate.queryForList("""
                    SELECT con.conname
                      FROM pg_constraint con
                      JOIN pg_class rel ON rel.oid = con.conrelid
                      JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                     WHERE rel.relname = ?
                       AND con.contype = 'u'
                       AND (SELECT array_agg(att.attname::text ORDER BY att.attname)
                              FROM unnest(con.conkey) AS k(attnum)
                              JOIN pg_attribute att
                                ON att.attrelid = con.conrelid AND att.attnum = k.attnum)
                           = ARRAY['product_type', 'project_id']
                    """, String.class, TABLE);

            if (stale.isEmpty()) {
                log.info("project_metabase_databases: no stale one-database-per-product-type constraint to drop.");
                return;
            }

            for (String name : stale) {
                // The name comes from the catalog, not from user input, but it is still an
                // identifier being concatenated -- quoted so an unusual generated name cannot break
                // the statement.
                jdbcTemplate.execute("ALTER TABLE " + TABLE + " DROP CONSTRAINT \"" + name + "\"");
                log.warn("project_metabase_databases: dropped stale unique constraint {} -- a product type "
                        + "can now hold several Metabase databases.", name);
            }
        } catch (Exception e) {
            // Never fatal: see the class comment. The app is fully usable without this.
            log.error("Could not drop the stale unique constraint on {}. Adding a second Metabase database "
                    + "for one product type will keep failing until this is run by hand: "
                    + "ALTER TABLE {} DROP CONSTRAINT <name>;", TABLE, TABLE, e);
        }
    }
}
