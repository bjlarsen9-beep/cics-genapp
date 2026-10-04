package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The H2 MOTOR table must match the column list, types and lengths in base/cntl/db2cre.jcl. */
@SpringBootTest
class MotorSchemaParityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void motorTableMatchesDb2Ddl() {
        assertThat(PolicySchemaParityTest.columns(jdbc, "MOTOR")).containsExactly(
                "POLICYNUMBER INTEGER NOT NULL",
                "MAKE CHARACTER(15)",
                "MODEL CHARACTER(15)",
                "VALUE INTEGER",
                "REGNUMBER CHARACTER(7)",
                "COLOUR CHARACTER(8)",
                "CC SMALLINT",
                "YEAROFMANUFACTURE DATE",
                "PREMIUM INTEGER",
                "ACCIDENTS INTEGER");
    }

    @Test
    void motorHasPrimaryKeyAndCascadingForeignKeyToPolicy() {
        assertThat(jdbc.queryForList("""
                SELECT TC.CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC
                WHERE TC.TABLE_NAME = 'MOTOR' ORDER BY TC.CONSTRAINT_TYPE
                """, String.class)).containsExactly("FOREIGN KEY", "PRIMARY KEY");
        assertThat(jdbc.queryForObject("""
                SELECT RC.DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS RC
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC ON TC.CONSTRAINT_NAME = RC.CONSTRAINT_NAME
                WHERE TC.TABLE_NAME = 'MOTOR'
                """, String.class)).isEqualTo("CASCADE");
    }
}
