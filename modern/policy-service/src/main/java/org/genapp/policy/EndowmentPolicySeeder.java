package org.genapp.policy;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds ENDOWMENT for one {@code ksdspoly.txt} record of type {@code E}. The record summary
 * ({@code WF-E-Policy-Data} in LGAPVS01) supplies WITHPROFITS, EQUITIES, MANAGEDFUND, FUNDNAME and LIFEASSURED;
 * TERM, SUMASSURED and PADDINGDATA are not in the file and come from the {@code endowment} INSERT in
 * {@code db2cre.jcl} for the same policy number.
 */
@Component
public class EndowmentPolicySeeder implements PolicySeeder {

    /** {@code WF-E-WITH-PROFITS PIC X}, offset within {@code WF-Policy-Data}. */
    static final int WITH_PROFITS = 0;
    /** {@code WF-E-EQUITIES PIC X}. */
    static final int EQUITIES = 1;
    /** {@code WF-E-MANAGED-FUND PIC X}. */
    static final int MANAGED_FUND = 2;
    /** {@code WF-E-FUND-NAME PIC X(10)}. */
    static final int FUND_NAME = 3;
    static final int FUND_NAME_LEN = 10;
    /** {@code WF-E-LIFE-ASSURED PIC X(30)} (one byte shorter than {@code CA-E-LIFE-ASSURED X(31)}). */
    static final int LIFE_ASSURED = 13;
    static final int LIFE_ASSURED_LEN = 30;

    private static final String INSERT_ENDOWMENT = """
            INSERT INTO ENDOWMENT (POLICYNUMBER, EQUITIES, WITHPROFITS, MANAGEDFUND, FUNDNAME, TERM, SUMASSURED,
                                   LIFEASSURED, PADDINGDATA)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Override
    public String policyType() {
        return "E";
    }

    @Override
    public void seed(KsdsPolyRecord record, Db2CreInserts jcl, JdbcTemplate jdbc) {
        Map<String, String> e = jcl.findByPolicyNumber("endowment", record.policyNumber()).orElse(Map.of());
        jdbc.update(INSERT_ENDOWMENT,
                record.policyNumber(),
                record.text(EQUITIES, 1),
                record.text(WITH_PROFITS, 1),
                record.text(MANAGED_FUND, 1),
                record.text(FUND_NAME, FUND_NAME_LEN),
                SeedDataLoader.integer(e.get("term")),
                SeedDataLoader.integer(e.get("sumassured")),
                record.text(LIFE_ASSURED, LIFE_ASSURED_LEN),
                e.get("paddingdata"));
    }
}
