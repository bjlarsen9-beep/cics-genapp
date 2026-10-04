package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The H2 POLICY table must match the column list, types and lengths in base/cntl/db2cre.jcl. */
@SpringBootTest
class PolicySchemaParityTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** Column list of an H2 table as "NAME TYPE[(length)] [NOT NULL]", in DDL order. */
    static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.query("""
                SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION
                """, (rs, i) -> rs.getString(1) + " " + rs.getString(2)
                + (rs.getObject(3) == null ? "" : "(" + rs.getInt(3) + ")")
                + ("NO".equals(rs.getString(4)) ? " NOT NULL" : ""), table);
    }

    @Test
    void policyTableMatchesDb2Ddl() {
        assertThat(columns(jdbc, "POLICY")).containsExactly(
                "POLICYNUMBER INTEGER NOT NULL",
                "CUSTOMERNUMBER INTEGER NOT NULL",
                "ISSUEDATE DATE",
                "EXPIRYDATE DATE",
                "POLICYTYPE CHARACTER(1)",
                "LASTCHANGED TIMESTAMP NOT NULL",
                "BROKERID INTEGER",
                "BROKERSREFERENCE CHARACTER(10)",
                "PAYMENT INTEGER",
                "COMMISSION SMALLINT");
    }

    @Test
    void policyNumberIdentityStartsAt1000001() {
        Map<String, Object> id = jdbc.queryForMap("""
                SELECT IS_IDENTITY, IDENTITY_START FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'POLICY' AND COLUMN_NAME = 'POLICYNUMBER'
                """);
        assertThat(id.get("IS_IDENTITY")).isEqualTo("YES");
        assertThat(((Number) id.get("IDENTITY_START")).longValue()).isEqualTo(1000001L);
    }
}
