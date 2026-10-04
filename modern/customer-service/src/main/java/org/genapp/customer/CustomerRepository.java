package org.genapp.customer;

import java.sql.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Db2 access for the CUSTOMER table; the Java side of LGICDB01. */
@Repository
public class CustomerRepository {

    private static final String SELECT_CUSTOMER = """
            SELECT FIRSTNAME,
                   LASTNAME,
                   DATEOFBIRTH,
                   HOUSENAME,
                   HOUSENUMBER,
                   POSTCODE,
                   PHONEMOBILE,
                   PHONEHOME,
                   EMAILADDRESS
            FROM CUSTOMER
            WHERE CUSTOMERNUMBER = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public CustomerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * LGICDB01 {@code GET-CUSTOMER-INFO}: the embedded SELECT. An empty result is SQLCODE 100.
     *
     * @param customerNumber {@code DB2-CUSTOMERNUMBER-INT}
     */
    public Optional<Customer> findByCustomerNumber(long customerNumber) {
        List<Customer> rows = jdbcTemplate.query(SELECT_CUSTOMER, (rs, rowNum) -> {
            Date dob = rs.getDate("DATEOFBIRTH");
            return new Customer(
                    Customer.formatCustomerNumber(customerNumber),
                    rtrim(rs.getString("FIRSTNAME")),
                    rtrim(rs.getString("LASTNAME")),
                    Customer.formatDate(dob == null ? null : dob.toLocalDate()),
                    rtrim(rs.getString("HOUSENAME")),
                    rtrim(rs.getString("HOUSENUMBER")),
                    rtrim(rs.getString("POSTCODE")),
                    0,
                    rtrim(rs.getString("PHONEMOBILE")),
                    rtrim(rs.getString("PHONEHOME")),
                    rtrim(rs.getString("EMAILADDRESS")));
        }, customerNumber);
        return rows.stream().findFirst();
    }

    static String rtrim(String value) {
        return value == null ? "" : value.stripTrailing();
    }
}
