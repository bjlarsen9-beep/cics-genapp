package org.genapp.policy;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Common policy fields ({@code CA-POLICY-COMMON} in {@code base/src/lgcmarea.cpy}).
 *
 * <p>Text fields carry the COBOL value with trailing CHAR padding removed. A NULL column gives the value the
 * COBOL host variable had after {@code INITIALIZE}: 0 for numerics, "" for text (LGIPDB01 skips the MOVE when
 * the indicator variable is -1).
 *
 * @param issueDate        {@code CA-ISSUE-DATE PIC X(10)}, ISO {@code yyyy-MM-dd}
 * @param expiryDate       {@code CA-EXPIRY-DATE PIC X(10)}, ISO {@code yyyy-MM-dd}
 * @param lastChanged      {@code CA-LASTCHANGED PIC X(26)}, Db2 timestamp string {@code yyyy-MM-dd-HH.mm.ss.SSSSSS}
 * @param brokerId         {@code CA-BROKERID PIC 9(10)}
 * @param brokersReference {@code CA-BROKERSREF PIC X(10)}
 * @param payment          {@code CA-PAYMENT PIC 9(6)}
 */
public record PolicyCommon(
        String issueDate,
        String expiryDate,
        String lastChanged,
        long brokerId,
        String brokersReference,
        long payment) {

    /** Db2 SQLCODE -305: NULL fetched into a host variable that has no indicator variable. */
    static final int SQLCODE_NULL_NO_INDICATOR = -305;

    /**
     * Maps the POLICY columns {@code ISSUEDATE, EXPIRYDATE, LASTCHANGED, BROKERID, BROKERSREFERENCE, PAYMENT}
     * of the current row, as selected by GET-ENDOW/HOUSE/MOTOR-DB2-INFO.
     *
     * <p>The dates are fetched without indicator variables, so a NULL fails with SQLCODE -305 (return code
     * {@code '90'}). BROKERID, BROKERSREFERENCE and PAYMENT have indicators; NULL leaves the INITIALIZEd value.
     * {@code MOVE DB2-BROKERID-INT/DB2-PAYMENT-INT} into unsigned {@code PIC 9(10)/9(6)} drops the sign and, for
     * payment, the high-order digits.
     */
    public static PolicyCommon fromPolicyColumns(ResultSet rs) throws SQLException {
        requireNonNull(rs, "ISSUEDATE", "EXPIRYDATE", "LASTCHANGED");
        return new PolicyCommon(
                Db2Format.date(rs.getDate("ISSUEDATE")),
                Db2Format.date(rs.getDate("EXPIRYDATE")),
                Db2Format.timestamp(rs.getTimestamp("LASTCHANGED")),
                Math.abs(Db2Format.number(rs, "BROKERID")),
                Db2Format.text(rs.getString("BROKERSREFERENCE")),
                Math.abs(Db2Format.number(rs, "PAYMENT")) % 1_000_000);
    }

    private static void requireNonNull(ResultSet rs, String... columns) throws SQLException {
        for (String column : columns) {
            if (rs.getObject(column) == null) {
                throw new SQLException("NULL value with no indicator variable", "22002", SQLCODE_NULL_NO_INDICATOR);
            }
        }
    }
}
