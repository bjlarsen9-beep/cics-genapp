package org.genapp.policy;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds the MOTOR row for a {@code ksdspoly.txt} record of type {@code M}.
 *
 * <p>The file's {@code WF-M-Policy-Data} (LGAPVS01) supplies MAKE ({@code WF-M-MAKE X(15)}), MODEL
 * ({@code WF-M-MODEL X(15)}), VALUE ({@code WF-M-VALUE 9(6)}) and REGNUMBER ({@code WF-M-REGNUMBER X(7)}).
 * COLOUR, CC, YEAROFMANUFACTURE, PREMIUM and ACCIDENTS come from the {@code motor} INSERT in {@code db2cre.jcl}
 * for the same policy number. Where both hold a value the file wins (policy 1's VALUE, see MIGRATION.md).
 */
@Component
public class MotorPolicySeeder implements PolicySeeder {

    static final int MAKE_OFFSET = 0;
    static final int MAKE_LENGTH = 15;
    static final int MODEL_OFFSET = 15;
    static final int MODEL_LENGTH = 15;
    static final int VALUE_OFFSET = 30;
    static final int VALUE_LENGTH = 6;
    static final int REGNUMBER_OFFSET = 36;
    static final int REGNUMBER_LENGTH = 7;

    private static final String INSERT_MOTOR = """
            INSERT INTO MOTOR (POLICYNUMBER, MAKE, MODEL, "VALUE", REGNUMBER, COLOUR, CC, YEAROFMANUFACTURE,
                               PREMIUM, ACCIDENTS)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Override
    public String policyType() {
        return "M";
    }

    @Override
    public void seed(KsdsPolyRecord record, Db2CreInserts jcl, JdbcTemplate jdbc) {
        Map<String, String> m = jcl.findByPolicyNumber("motor", record.policyNumber()).orElse(Map.of());
        jdbc.update(INSERT_MOTOR,
                record.policyNumber(),
                record.text(MAKE_OFFSET, MAKE_LENGTH),
                record.text(MODEL_OFFSET, MODEL_LENGTH),
                Integer.valueOf(record.raw(VALUE_OFFSET, VALUE_LENGTH)),
                record.text(REGNUMBER_OFFSET, REGNUMBER_LENGTH),
                m.get("colour"),
                SeedDataLoader.integer(m.get("cc")),
                SeedDataLoader.date(m.get("yearofmanufacture")),
                SeedDataLoader.integer(m.get("premium")),
                SeedDataLoader.integer(m.get("accidents")));
    }
}
