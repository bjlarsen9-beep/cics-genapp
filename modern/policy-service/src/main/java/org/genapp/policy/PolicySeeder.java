package org.genapp.policy;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the type-specific Db2 table (ENDOWMENT, HOUSE, MOTOR or COMMERCIAL) for one {@code ksdspoly.txt} record.
 * Implementations are Spring beans; {@link SeedDataLoader} calls the one whose {@link #policyType()} matches
 * {@link KsdsPolyRecord#policyType()} after it has inserted the POLICY row.
 *
 * <p>Source precedence: values present in the {@code ksdspoly.txt} summary win; columns the summary lacks come
 * from the {@code db2cre.jcl} INSERT for the same policy number. Disagreements are documented in MIGRATION.md.
 */
public interface PolicySeeder {

    /** {@code E}, {@code H}, {@code M} or {@code C}. */
    String policyType();

    void seed(KsdsPolyRecord record, Db2CreInserts jcl, JdbcTemplate jdbc);
}
