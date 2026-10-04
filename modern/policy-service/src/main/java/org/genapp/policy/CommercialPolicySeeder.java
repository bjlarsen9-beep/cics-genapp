package org.genapp.policy;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds the COMMERCIAL row for a {@code ksdspoly.txt} record of type {@code C}.
 *
 * <p>The record's {@code WF-C-Policy-Data} (LGAPVS01) holds {@code WF-B-Postcode X(8)}, {@code WF-B-Status 9(4)}
 * and {@code WF-B-Customer X(31)}; those win over the {@code db2cre.jcl} INSERT with two exceptions: a status that
 * fails the COBOL NUMERIC test (the sample file has {@code "A  0"}) cannot be stored in a SMALLINT, so the
 * {@code db2cre.jcl} value is kept; and a file customer that is just the 31-byte truncation of a longer
 * {@code db2cre.jcl} value keeps the full value. Every other column comes from {@code db2cre.jcl}.
 */
@Component
public class CommercialPolicySeeder implements PolicySeeder {

    static final int POSTCODE_OFFSET = 0;
    static final int POSTCODE_LENGTH = 8;
    static final int STATUS_OFFSET = 8;
    static final int STATUS_LENGTH = 4;
    static final int CUSTOMER_OFFSET = 12;
    static final int CUSTOMER_LENGTH = 31;

    private static final String INSERT_COMMERCIAL = """
            INSERT INTO COMMERCIAL (POLICYNUMBER, REQUESTDATE, STARTDATE, RENEWALDATE, ADDRESS, ZIPCODE,
                                    LATITUDEN, LONGITUDEW, CUSTOMER, PROPERTYTYPE, FIREPERIL, FIREPREMIUM,
                                    CRIMEPERIL, CRIMEPREMIUM, FLOODPERIL, FLOODPREMIUM, WEATHERPERIL,
                                    WEATHERPREMIUM, STATUS, REJECTIONREASON)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Override
    public String policyType() {
        return "C";
    }

    @Override
    public void seed(KsdsPolyRecord record, Db2CreInserts jcl, JdbcTemplate jdbc) {
        Map<String, String> c = jcl.findByPolicyNumber("commercial", record.policyNumber()).orElse(Map.of());
        jdbc.update(INSERT_COMMERCIAL,
                record.policyNumber(),
                SeedDataLoader.timestamp(c.get("requestdate")),
                SeedDataLoader.date(c.get("startdate")),
                SeedDataLoader.date(c.get("renewaldate")),
                c.get("address"),
                record.text(POSTCODE_OFFSET, POSTCODE_LENGTH),
                c.get("latituden"),
                c.get("longitudew"),
                customer(record.text(CUSTOMER_OFFSET, CUSTOMER_LENGTH), c.get("customer")),
                c.get("propertytype"),
                SeedDataLoader.integer(c.get("fireperil")),
                SeedDataLoader.integer(c.get("firepremium")),
                SeedDataLoader.integer(c.get("crimeperil")),
                SeedDataLoader.integer(c.get("crimepremium")),
                SeedDataLoader.integer(c.get("floodperil")),
                SeedDataLoader.integer(c.get("floodpremium")),
                SeedDataLoader.integer(c.get("weatherperil")),
                SeedDataLoader.integer(c.get("weatherpremium")),
                status(record.raw(STATUS_OFFSET, STATUS_LENGTH), c.get("status")),
                c.get("rejectionreason"));
    }

    /** {@code WF-B-Status PIC 9(4)} if it is NUMERIC, else the {@code db2cre.jcl} Status. */
    static Integer status(String fileStatus, String jclStatus) {
        return fileStatus.chars().allMatch(ch -> ch >= '0' && ch <= '9')
                ? Integer.valueOf(fileStatus)
                : SeedDataLoader.integer(jclStatus);
    }

    /** {@code WF-B-Customer X(31)}, unless it is only the truncated form of the longer {@code db2cre.jcl} value. */
    static String customer(String fileCustomer, String jclCustomer) {
        if (jclCustomer != null && jclCustomer.length() > CUSTOMER_LENGTH
                && jclCustomer.substring(0, CUSTOMER_LENGTH).stripTrailing().equals(fileCustomer)) {
            return jclCustomer;
        }
        return fileCustomer;
    }
}
