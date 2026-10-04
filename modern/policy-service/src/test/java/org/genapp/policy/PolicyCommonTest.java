package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** CA-POLICY-COMMON moves shared by GET-ENDOW/HOUSE/MOTOR-DB2-INFO. */
@SpringBootTest
class PolicyCommonTest {

    @Autowired
    private JdbcTemplate jdbc;

    private PolicyCommon select(String issueDate, String expiryDate, String lastChanged,
                                String brokerId, String brokersRef, String payment) {
        return jdbc.queryForObject("SELECT CAST(" + issueDate + " AS DATE) ISSUEDATE, CAST(" + expiryDate
                + " AS DATE) EXPIRYDATE, CAST(" + lastChanged + " AS TIMESTAMP) LASTCHANGED, CAST(" + brokerId
                + " AS INTEGER) BROKERID, CAST(" + brokersRef + " AS CHAR(10)) BROKERSREFERENCE, CAST(" + payment
                + " AS INTEGER) PAYMENT", (rs, i) -> PolicyCommon.fromPolicyColumns(rs));
    }

    @Test
    void mapsDb2ValuesToCommareaFormat() {
        assertThat(select("'2014-07-01'", "'2015-06-30'", "'2014-07-01 10:11:12.5'", "42", "'REF1'", "750"))
                .isEqualTo(new PolicyCommon("2014-07-01", "2015-06-30", "2014-07-01-10.11.12.500000", 42, "REF1", 750));
    }

    @Test
    void nullIndicatorColumnsKeepInitializedValues() {
        assertThat(select("'2014-07-01'", "'2015-06-30'", "'2014-07-01 10:11:12'", "NULL", "NULL", "NULL"))
                .extracting(PolicyCommon::brokerId, PolicyCommon::brokersReference, PolicyCommon::payment)
                .containsExactly(0L, "", 0L);
    }

    @Test
    void unsignedMovesDropSignAndHighOrderDigits() {
        assertThat(select("'2014-07-01'", "'2015-06-30'", "'2014-07-01 10:11:12'", "-7", "''", "-1234567"))
                .extracting(PolicyCommon::brokerId, PolicyCommon::payment)
                .containsExactly(7L, 234567L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void nullDateWithoutIndicatorIsSqlcode305(int nullColumn) {
        String[] v = {"'2014-07-01'", "'2015-06-30'", "'2014-07-01 10:11:12'"};
        v[nullColumn] = "NULL";
        assertThatThrownBy(() -> select(v[0], v[1], v[2], "1", "''", "1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getErrorCode()).isEqualTo(-305);
    }
}
