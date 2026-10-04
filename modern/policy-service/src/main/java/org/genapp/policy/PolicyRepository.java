package org.genapp.policy;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared Db2 access for the POLICY table. Type-specific SELECTs live in the {@link PolicyTypeInquiry} beans. */
@Repository
public class PolicyRepository {

    private static final String SELECT_POLICY_TYPE = """
            SELECT POLICYTYPE
            FROM POLICY
            WHERE CUSTOMERNUMBER = ?
              AND POLICYNUMBER = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Replaces the caller-supplied {@code CA-REQUEST-ID} (01IEND/01IHOU/01IMOT/01ICOM): the REST path has no
     * policy type, so it is read from POLICY. An empty result means no such customer/policy pair (SQLCODE 100).
     */
    public Optional<String> findPolicyType(long customerNumber, long policyNumber) {
        List<String> rows = jdbcTemplate.query(SELECT_POLICY_TYPE,
                (rs, rowNum) -> Db2Format.text(rs.getString("POLICYTYPE")), customerNumber, policyNumber);
        return rows.stream().findFirst();
    }
}
