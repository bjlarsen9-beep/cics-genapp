package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The H2 HOUSE table must match the column list, types and lengths in base/cntl/db2cre.jcl. */
@SpringBootTest
class HouseSchemaParityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void houseTableMatchesDb2Ddl() {
        assertThat(PolicySchemaParityTest.columns(jdbc, "HOUSE")).containsExactly(
                "POLICYNUMBER INTEGER NOT NULL",
                "PROPERTYTYPE CHARACTER(15)",
                "BEDROOMS SMALLINT",
                "VALUE INTEGER",
                "HOUSENAME CHARACTER(20)",
                "HOUSENUMBER CHARACTER(4)",
                "POSTCODE CHARACTER(8)");
    }

    @Test
    void housePolicyNumberIsPrimaryKeyAndCascadingForeignKeyToPolicy() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE TABLE_NAME = 'HOUSE' AND CONSTRAINT_TYPE = 'PRIMARY KEY'""", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT rc.DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS pk ON pk.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
                WHERE tc.TABLE_NAME = 'HOUSE' AND pk.TABLE_NAME = 'POLICY'""", String.class)).isEqualTo("CASCADE");
    }
}
