package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The H2 COMMERCIAL table must match the column list, types and lengths in base/cntl/db2cre.jcl. */
@SpringBootTest
class CommercialSchemaParityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void commercialTableMatchesDb2Ddl() {
        assertThat(PolicySchemaParityTest.columns(jdbc, "COMMERCIAL")).containsExactly(
                "POLICYNUMBER INTEGER NOT NULL",
                "REQUESTDATE TIMESTAMP",
                "STARTDATE DATE",
                "RENEWALDATE DATE",
                "ADDRESS CHARACTER(255)",
                "ZIPCODE CHARACTER(8)",
                "LATITUDEN CHARACTER(11)",
                "LONGITUDEW CHARACTER(11)",
                "CUSTOMER CHARACTER(255)",
                "PROPERTYTYPE CHARACTER(255)",
                "FIREPERIL SMALLINT",
                "FIREPREMIUM INTEGER",
                "CRIMEPERIL SMALLINT",
                "CRIMEPREMIUM INTEGER",
                "FLOODPERIL SMALLINT",
                "FLOODPREMIUM INTEGER",
                "WEATHERPERIL SMALLINT",
                "WEATHERPREMIUM INTEGER",
                "STATUS SMALLINT",
                "REJECTIONREASON CHARACTER(255)");
    }

    @Test
    void commercialHasPrimaryKeyAndCascadingForeignKeyToPolicy() {
        assertThat(jdbc.queryForList("""
                SELECT TC.CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC
                WHERE TC.TABLE_NAME = 'COMMERCIAL' ORDER BY TC.CONSTRAINT_TYPE""", String.class))
                .containsExactly("FOREIGN KEY", "PRIMARY KEY");
        assertThat(jdbc.queryForObject("""
                SELECT RC.DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS RC
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC ON TC.CONSTRAINT_NAME = RC.CONSTRAINT_NAME
                WHERE TC.TABLE_NAME = 'COMMERCIAL'""", String.class)).isEqualTo("CASCADE");
    }
}
