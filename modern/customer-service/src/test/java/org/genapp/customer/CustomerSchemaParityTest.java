package org.genapp.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The H2 CUSTOMER table must match the column list, types and lengths in base/cntl/db2cre.jcl. */
@SpringBootTest
class CustomerSchemaParityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void customerTableMatchesDb2Ddl() {
        List<String> actual = jdbc.query("""
                SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'CUSTOMER' ORDER BY ORDINAL_POSITION
                """, (rs, i) -> rs.getString(1) + " " + rs.getString(2)
                + (rs.getObject(3) == null ? "" : "(" + rs.getInt(3) + ")"));
        assertThat(actual).containsExactly(
                "CUSTOMERNUMBER INTEGER",
                "FIRSTNAME CHARACTER(10)",
                "LASTNAME CHARACTER(20)",
                "DATEOFBIRTH DATE",
                "HOUSENAME CHARACTER(20)",
                "HOUSENUMBER CHARACTER(4)",
                "POSTCODE CHARACTER(8)",
                "PHONEHOME CHARACTER(20)",
                "PHONEMOBILE CHARACTER(20)",
                "EMAILADDRESS CHARACTER(100)");
    }

    @Test
    void customerNumberIdentityStartsAt1000001() {
        Map<String, Object> id = jdbc.queryForMap("""
                SELECT IS_IDENTITY, IDENTITY_START FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'CUSTOMER' AND COLUMN_NAME = 'CUSTOMERNUMBER'
                """);
        assertThat(id.get("IS_IDENTITY")).isEqualTo("YES");
        assertThat(((Number) id.get("IDENTITY_START")).longValue()).isEqualTo(1000001L);
    }
}
