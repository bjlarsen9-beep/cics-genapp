package org.genapp.policy;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds the HOUSE row for each {@code ksdspoly.txt} record of type {@code H}.
 *
 * <p>The record's {@code WF-H-Policy-Data} (LGAPVS01) holds {@code WF-H-PROPERTY-TYPE X(15)},
 * {@code WF-H-BEDROOMS 9(3)}, {@code WF-H-VALUE 9(8)}, {@code WF-H-POSTCODE X(8)} and
 * {@code WF-H-HOUSE-NAME X(9)}; these win over {@code db2cre.jcl}. {@code houseNumber} is not in the file and
 * comes from the matching {@code house} INSERT. Two exceptions, documented in MIGRATION.md (House quirks):
 * a {@code 9(n)} field that is not all digits is invalid data and the INSERT value is used instead, and a
 * 9-character house name that is the start of a longer INSERT name is the truncated summary of that name.
 */
@Component
public class HousePolicySeeder implements PolicySeeder {

    static final int PROPERTY_TYPE_OFFSET = 0;
    static final int PROPERTY_TYPE_LENGTH = 15;
    static final int BEDROOMS_OFFSET = 15;
    static final int BEDROOMS_LENGTH = 3;
    static final int VALUE_OFFSET = 18;
    static final int VALUE_LENGTH = 8;
    static final int POSTCODE_OFFSET = 26;
    static final int POSTCODE_LENGTH = 8;
    static final int HOUSE_NAME_OFFSET = 34;
    static final int HOUSE_NAME_LENGTH = 9;

    private static final String INSERT_HOUSE = """
            INSERT INTO HOUSE (POLICYNUMBER, PROPERTYTYPE, BEDROOMS, "VALUE", HOUSENAME, HOUSENUMBER, POSTCODE)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    @Override
    public String policyType() {
        return "H";
    }

    @Override
    public void seed(KsdsPolyRecord record, Db2CreInserts jcl, JdbcTemplate jdbc) {
        Map<String, String> h = jcl.findByPolicyNumber("house", record.policyNumber()).orElse(Map.of());
        jdbc.update(INSERT_HOUSE,
                record.policyNumber(),
                record.text(PROPERTY_TYPE_OFFSET, PROPERTY_TYPE_LENGTH),
                number(record.raw(BEDROOMS_OFFSET, BEDROOMS_LENGTH), h.get("bedrooms")),
                number(record.raw(VALUE_OFFSET, VALUE_LENGTH), h.get("value")),
                houseName(record.text(HOUSE_NAME_OFFSET, HOUSE_NAME_LENGTH), h.get("housename")),
                h.get("housenumber"),
                record.text(POSTCODE_OFFSET, POSTCODE_LENGTH));
    }

    /** A {@code PIC 9(n)} summary field; if it is not all digits the db2cre.jcl literal is used. */
    static Integer number(String field, String db2creLiteral) {
        return field.chars().allMatch(Character::isDigit)
                ? Integer.valueOf(field)
                : SeedDataLoader.integer(db2creLiteral);
    }

    /** {@code WF-H-HOUSE-NAME X(9)} is a truncated {@code CA-H-HOUSE-NAME X(20)}: keep the longer db2cre name. */
    static String houseName(String summary, String db2creName) {
        if (db2creName != null && !summary.isEmpty()
                && db2creName.stripTrailing().length() > HOUSE_NAME_LENGTH && db2creName.startsWith(summary)) {
            return db2creName;
        }
        return summary;
    }
}
