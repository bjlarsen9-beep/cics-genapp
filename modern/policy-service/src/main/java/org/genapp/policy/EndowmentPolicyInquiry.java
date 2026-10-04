package org.genapp.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * LGIPDB01 paragraph {@code GET-ENDOW-DB2-INFO} (request ID {@code 01IEND}): one row of POLICY joined with
 * ENDOWMENT for a customer and policy number.
 */
@Repository
public class EndowmentPolicyInquiry implements PolicyTypeInquiry {

    /** Same column list, join and WHERE as the embedded SELECT in {@code GET-ENDOW-DB2-INFO}. */
    static final String SELECT_ENDOWMENT = """
            SELECT  ISSUEDATE,
                    EXPIRYDATE,
                    LASTCHANGED,
                    BROKERID,
                    BROKERSREFERENCE,
                    PAYMENT,
                    WITHPROFITS,
                    EQUITIES,
                    MANAGEDFUND,
                    FUNDNAME,
                    TERM,
                    SUMASSURED,
                    LIFEASSURED,
                    PADDINGDATA,
                    LENGTH(PADDINGDATA) AS PADDINGDATA_LEN
            FROM  POLICY,ENDOWMENT
            WHERE ( POLICY.POLICYNUMBER =
                       ENDOWMENT.POLICYNUMBER   AND
                    POLICY.CUSTOMERNUMBER = ?   AND
                    POLICY.POLICYNUMBER = ?     )
            """;

    /** Db2 SQLCODE -305: NULL fetched into a host variable that has no indicator variable. */
    static final int SQLCODE_NULL_NO_INDICATOR = -305;

    private final JdbcTemplate jdbcTemplate;

    public EndowmentPolicyInquiry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String policyType() {
        return "E";
    }

    @Override
    public String requestId() {
        return "01IEND";
    }

    @Override
    public Optional<Policy> inquire(long customerNumber, long policyNumber) {
        List<Policy> rows = jdbcTemplate.query(SELECT_ENDOWMENT,
                (rs, rowNum) -> new Policy(
                        Db2Format.key(customerNumber),
                        Db2Format.key(policyNumber),
                        policyType(),
                        PolicyCommon.fromPolicyColumns(rs),
                        details(rs)),
                customerNumber, policyNumber);
        return rows.stream().findFirst();
    }

    /**
     * The {@code DB2-ENDOW-FIXED} moves. Only PADDINGDATA (and its length) has an indicator variable
     * ({@code IND-E-PADDINGDATA}); a NULL in any other ENDOWMENT column fails the SELECT with SQLCODE -305,
     * which the paragraph reports as {@code '90'}.
     */
    static EndowmentDetails details(ResultSet rs) throws SQLException {
        String paddingData = rs.getString("PADDINGDATA");
        return new EndowmentDetails(
                Db2Format.text(required(rs, "WITHPROFITS")),
                Db2Format.text(required(rs, "EQUITIES")),
                Db2Format.text(required(rs, "MANAGEDFUND")),
                Db2Format.text(required(rs, "FUNDNAME")),
                (int) unsignedDisplay(requiredNumber(rs, "TERM"), 100),
                unsignedDisplay(requiredNumber(rs, "SUMASSURED"), 1_000_000),
                Db2Format.text(required(rs, "LIFEASSURED")),
                paddingData == null ? "" : paddingData);
    }

    /**
     * COBOL {@code MOVE} of a signed binary host variable ({@code DB2-E-TERM-SINT}, {@code DB2-E-SUMASSURED-INT})
     * to an unsigned display field ({@code PIC 99}, {@code PIC 9(6)}): sign dropped, high-order digits truncated.
     */
    static long unsignedDisplay(long value, long modulus) {
        return Math.abs(value) % modulus;
    }

    private static String required(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            throw nullWithoutIndicator();
        }
        return value;
    }

    private static long requiredNumber(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull()) {
            throw nullWithoutIndicator();
        }
        return value;
    }

    private static SQLException nullWithoutIndicator() {
        return new SQLException("NULL value fetched into a host variable without an indicator variable", "22002",
                SQLCODE_NULL_NO_INDICATOR);
    }
}
