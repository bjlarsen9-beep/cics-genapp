package org.genapp.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * LGIPDB01 {@code GET-MOTOR-DB2-INFO} (request ID {@code 01IMOT}): POLICY joined with MOTOR for one customer and
 * policy number.
 *
 * <p>NULL handling follows the COBOL host variables: only BROKERID, BROKERSREFERENCE and PAYMENT have indicator
 * variables, so a NULL there leaves the {@code INITIALIZE}d value (0 or ""). Any other selected column has no
 * indicator; Db2 rejects a NULL in such a column with SQLCODE -305, which the paragraph reports as {@code '90'}.
 */
@Repository
public class MotorPolicyInquiry implements PolicyTypeInquiry {

    /** Db2 SQLCODE for a NULL value fetched into a host variable without an indicator variable. */
    static final int SQLCODE_NULL_NO_INDICATOR = -305;

    private static final String SELECT_MOTOR = """
            SELECT ISSUEDATE,
                   EXPIRYDATE,
                   LASTCHANGED,
                   BROKERID,
                   BROKERSREFERENCE,
                   PAYMENT,
                   MAKE,
                   MODEL,
                   "VALUE",
                   REGNUMBER,
                   COLOUR,
                   CC,
                   YEAROFMANUFACTURE,
                   PREMIUM,
                   ACCIDENTS
            FROM POLICY, MOTOR
            WHERE ( POLICY.POLICYNUMBER = MOTOR.POLICYNUMBER AND
                    POLICY.CUSTOMERNUMBER = ? AND
                    POLICY.POLICYNUMBER = ? )
            """;

    private final JdbcTemplate jdbcTemplate;

    public MotorPolicyInquiry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String policyType() {
        return "M";
    }

    @Override
    public String requestId() {
        return "01IMOT";
    }

    @Override
    public Optional<Policy> inquire(long customerNumber, long policyNumber) {
        List<Policy> rows = jdbcTemplate.query(SELECT_MOTOR,
                (rs, rowNum) -> map(rs, customerNumber, policyNumber), customerNumber, policyNumber);
        return rows.stream().findFirst();
    }

    private Policy map(ResultSet rs, long customerNumber, long policyNumber) throws SQLException {
        requireNonNull(rs, "ISSUEDATE", "EXPIRYDATE", "LASTCHANGED", "MAKE", "MODEL", "VALUE", "REGNUMBER", "COLOUR",
                "CC", "YEAROFMANUFACTURE", "PREMIUM", "ACCIDENTS");
        MotorDetails details = new MotorDetails(
                Db2Format.text(rs.getString("MAKE")),
                Db2Format.text(rs.getString("MODEL")),
                toUnsigned(rs.getLong("VALUE"), 6),
                Db2Format.text(rs.getString("REGNUMBER")),
                Db2Format.text(rs.getString("COLOUR")),
                (int) toUnsigned(rs.getInt("CC"), 4),
                Db2Format.date(rs.getDate("YEAROFMANUFACTURE")),
                toUnsigned(rs.getLong("PREMIUM"), 6),
                toUnsigned(rs.getLong("ACCIDENTS"), 6));
        return new Policy(Db2Format.key(customerNumber), Db2Format.key(policyNumber), policyType(),
                PolicyCommon.fromPolicyColumns(rs), details);
    }

    /** Host variables without an {@code INDICATOR}: a NULL is SQLCODE -305 in Db2. */
    private static void requireNonNull(ResultSet rs, String... columns) throws SQLException {
        for (String column : columns) {
            if (rs.getObject(column) == null) {
                throw new SQLException("NULL value with no indicator variable", "22002", SQLCODE_NULL_NO_INDICATOR);
            }
        }
    }

    /**
     * COBOL {@code MOVE} of a signed binary host variable ({@code PIC S9(n) COMP}) to an unsigned {@code PIC 9(digits)}
     * field: the sign is dropped and only the low-order {@code digits} digits are kept.
     */
    static long toUnsigned(long value, int digits) {
        return Math.abs(value) % (long) Math.pow(10, digits);
    }
}
