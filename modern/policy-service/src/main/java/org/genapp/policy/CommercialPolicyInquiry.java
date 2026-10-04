package org.genapp.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * LGIPDB01 {@code GET-Commercial-DB2-INFO-1} (request ID {@code 01ICOM}): one Commercial policy by customer and
 * policy number.
 *
 * <p>Unlike the Endowment/House/Motor paragraphs, this SELECT reads no POLICY columns: {@code CA-POLICY-COMMON}
 * gets {@code COMMERCIAL.StartDate}, {@code RenewalDate} and {@code RequestDate} as issue date, expiry date and
 * last-changed timestamp, while {@code CA-BROKERID}, {@code CA-BROKERSREF} and {@code CA-PAYMENT} keep the values
 * from {@code INITIALIZE DB2-POLICY} (zero / spaces). The SELECT has no indicator variables, so a NULL in any
 * selected column is Db2 SQLCODE -305 ({@code '90'}).
 */
@Repository
public class CommercialPolicyInquiry implements PolicyTypeInquiry {

    /** Same column list, join and WHERE as {@code GET-Commercial-DB2-INFO-1}. */
    static final String SELECT_COMMERCIAL = """
            SELECT
                  RequestDate,
                  StartDate,
                  RenewalDate,
                  Address,
                  Zipcode,
                  LatitudeN,
                  LongitudeW,
                  Customer,
                  PropertyType,
                  FirePeril,
                  FirePremium,
                  CrimePeril,
                  CrimePremium,
                  FloodPeril,
                  FloodPremium,
                  WeatherPeril,
                  WeatherPremium,
                  Status,
                  RejectionReason
            FROM  POLICY,COMMERCIAL
            WHERE ( POLICY.POLICYNUMBER =
                       COMMERCIAL.POLICYNUMBER   AND
                    POLICY.CUSTOMERNUMBER = ?    AND
                    POLICY.POLICYNUMBER = ?      )
            """;

    /** Db2 SQLCODE -305: NULL fetched into a host variable that has no indicator variable. */
    static final int SQLCODE_NULL_WITHOUT_INDICATOR = -305;

    private final JdbcTemplate jdbcTemplate;

    public CommercialPolicyInquiry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String policyType() {
        return "C";
    }

    @Override
    public String requestId() {
        return "01ICOM";
    }

    @Override
    public Optional<Policy> inquire(long customerNumber, long policyNumber) {
        List<Policy> rows = jdbcTemplate.query(SELECT_COMMERCIAL,
                (rs, rowNum) -> map(rs, customerNumber, policyNumber), customerNumber, policyNumber);
        return rows.stream().findFirst();
    }

    private Policy map(ResultSet rs, long customerNumber, long policyNumber) throws SQLException {
        requireNoNulls(rs);
        // INTO :DB2-LASTCHANGED, :DB2-ISSUEDATE, :DB2-EXPIRYDATE; broker/payment stay as INITIALIZE left them.
        PolicyCommon common = new PolicyCommon(
                Db2Format.date(rs.getDate("STARTDATE")),
                Db2Format.date(rs.getDate("RENEWALDATE")),
                Db2Format.timestamp(rs.getTimestamp("REQUESTDATE")),
                0,
                "",
                0);
        CommercialDetails details = new CommercialDetails(
                Db2Format.text(rs.getString("ADDRESS")),
                Db2Format.text(rs.getString("ZIPCODE")),
                Db2Format.text(rs.getString("LATITUDEN")),
                Db2Format.text(rs.getString("LONGITUDEW")),
                Db2Format.text(rs.getString("CUSTOMER")),
                Db2Format.text(rs.getString("PROPERTYTYPE")),
                unsigned(rs.getLong("FIREPERIL"), 4),
                unsigned(rs.getLong("FIREPREMIUM"), 8),
                unsigned(rs.getLong("CRIMEPERIL"), 4),
                unsigned(rs.getLong("CRIMEPREMIUM"), 8),
                unsigned(rs.getLong("FLOODPERIL"), 4),
                unsigned(rs.getLong("FLOODPREMIUM"), 8),
                unsigned(rs.getLong("WEATHERPERIL"), 4),
                unsigned(rs.getLong("WEATHERPREMIUM"), 8),
                unsigned(rs.getLong("STATUS"), 4),
                Db2Format.text(rs.getString("REJECTIONREASON")));
        return new Policy(Db2Format.key(customerNumber), Db2Format.key(policyNumber), policyType(), common, details);
    }

    /** No indicator variables in the paragraph's INTO list: any NULL column fails the FETCH with SQLCODE -305. */
    private static void requireNoNulls(ResultSet rs) throws SQLException {
        int columns = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= columns; i++) {
            if (rs.getObject(i) == null) {
                throw new SQLException("Null value in a column without an indicator variable", "22002",
                        SQLCODE_NULL_WITHOUT_INDICATOR);
            }
        }
    }

    /**
     * COBOL {@code MOVE} of a signed binary host variable ({@code S9(4)}/{@code S9(9) COMP}) into an unsigned
     * {@code PIC 9(digits)} field: the sign is dropped and high-order digits are truncated.
     */
    static int unsigned(long value, int digits) {
        long modulus = (long) Math.pow(10, digits);
        return (int) (Math.abs(value) % modulus);
    }
}
