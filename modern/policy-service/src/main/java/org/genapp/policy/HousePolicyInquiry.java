package org.genapp.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * LGIPDB01 paragraph {@code GET-HOUSE-DB2-INFO} (request ID {@code 01IHOU}): singleton SELECT on the join of
 * POLICY and HOUSE for one customer and policy number.
 */
@Repository
public class HousePolicyInquiry implements PolicyTypeInquiry {

    /** Same column list, join and WHERE as the {@code EXEC SQL SELECT} in {@code GET-HOUSE-DB2-INFO}. */
    static final String SELECT_HOUSE = """
            SELECT  ISSUEDATE,
                    EXPIRYDATE,
                    LASTCHANGED,
                    BROKERID,
                    BROKERSREFERENCE,
                    PAYMENT,
                    PROPERTYTYPE,
                    BEDROOMS,
                    "VALUE",
                    HOUSENAME,
                    HOUSENUMBER,
                    POSTCODE
            FROM  POLICY,HOUSE
            WHERE ( POLICY.POLICYNUMBER = HOUSE.POLICYNUMBER AND
                    POLICY.CUSTOMERNUMBER = ?                AND
                    POLICY.POLICYNUMBER = ?                  )
            """;

    /**
     * Columns fetched into host variables without an {@code INDICATOR}: a NULL in any of them makes Db2 return
     * SQLCODE -305, which the paragraph turns into {@code '90'}. Only BROKERID, BROKERSREFERENCE and PAYMENT
     * have indicators.
     */
    static final List<String> COLUMNS_WITHOUT_INDICATOR = List.of(
            "ISSUEDATE", "EXPIRYDATE", "LASTCHANGED",
            "PROPERTYTYPE", "BEDROOMS", "VALUE", "HOUSENAME", "HOUSENUMBER", "POSTCODE");

    static final int SQLCODE_NULL_WITHOUT_INDICATOR = -305;

    private final JdbcTemplate jdbcTemplate;

    public HousePolicyInquiry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String policyType() {
        return "H";
    }

    @Override
    public String requestId() {
        return "01IHOU";
    }

    @Override
    public Optional<Policy> inquire(long customerNumber, long policyNumber) {
        List<Policy> rows = jdbcTemplate.query(SELECT_HOUSE,
                (rs, rowNum) -> toPolicy(rs, customerNumber, policyNumber), customerNumber, policyNumber);
        if (rows.size() > 1) {
            // Singleton SELECT returning more than one row: SQLCODE -811, a '90' in COBOL.
            throw new IncorrectResultSizeDataAccessException(1, rows.size());
        }
        return rows.stream().findFirst();
    }

    /** {@code IF SQLCODE = 0} branch: move the host variables into {@code CA-POLICY-COMMON} and {@code CA-HOUSE}. */
    private Policy toPolicy(ResultSet rs, long customerNumber, long policyNumber) throws SQLException {
        for (String column : COLUMNS_WITHOUT_INDICATOR) {
            if (rs.getObject(column) == null) {
                throw new SQLException("NULL value fetched without an indicator variable", "22002",
                        SQLCODE_NULL_WITHOUT_INDICATOR);
            }
        }
        HouseDetails details = new HouseDetails(
                Db2Format.text(rs.getString("PROPERTYTYPE")),
                // MOVE DB2-H-BEDROOMS-SINT TO DB2-H-BEDROOMS (PIC 9(3)): sign dropped, high-order digits truncated
                Math.abs(rs.getLong("BEDROOMS")) % 1_000,
                // MOVE DB2-H-VALUE-INT TO DB2-H-VALUE (PIC 9(8)): sign dropped, high-order digits truncated
                Math.abs(rs.getLong("VALUE")) % 100_000_000,
                Db2Format.text(rs.getString("HOUSENAME")),
                Db2Format.text(rs.getString("HOUSENUMBER")),
                Db2Format.text(rs.getString("POSTCODE")));
        return new Policy(Db2Format.key(customerNumber), Db2Format.key(policyNumber), policyType(),
                PolicyCommon.fromPolicyColumns(rs), details);
    }
}
