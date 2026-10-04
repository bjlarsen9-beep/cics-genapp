package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The H2 ENDOWMENT table must match the column list, types and lengths in base/cntl/db2cre.jcl. */
@SpringBootTest
class EndowmentSchemaParityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void endowmentTableMatchesDb2Ddl() {
        assertThat(PolicySchemaParityTest.columns(jdbc, "ENDOWMENT")).containsExactly(
                "POLICYNUMBER INTEGER NOT NULL",
                "EQUITIES CHARACTER(1)",
                "WITHPROFITS CHARACTER(1)",
                "MANAGEDFUND CHARACTER(1)",
                "FUNDNAME CHARACTER(10)",
                "TERM SMALLINT",
                "SUMASSURED INTEGER",
                "LIFEASSURED CHARACTER(31)",
                "PADDINGDATA CHARACTER VARYING(32606)");
    }

    @Test
    void endowmentPrimaryKeyAndCascadingForeignKeyToPolicy() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE TABLE_NAME = 'ENDOWMENT' AND CONSTRAINT_TYPE = 'PRIMARY KEY'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT rc.DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS uc ON uc.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
                WHERE tc.TABLE_NAME = 'ENDOWMENT' AND uc.TABLE_NAME = 'POLICY'
                """, String.class)).isEqualTo("CASCADE");
    }
}
