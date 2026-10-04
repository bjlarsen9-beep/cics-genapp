package org.genapp.policy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads the GenApp sample policies into the local H2 tables. {@code base/data/ksdspoly.txt} decides which policies
 * exist (type, customer number, policy number); POLICY columns that the file does not hold (dates, broker,
 * payment, commission) come from the matching {@code policy} INSERT in {@code base/cntl/db2cre.jcl}. Type tables
 * are filled by the {@link PolicySeeder} beans.
 */
@Component
@ConditionalOnProperty(name = "genapp.seed.enabled", havingValue = "true")
public class SeedDataLoader implements ApplicationRunner {

    private static final String INSERT_POLICY = """
            INSERT INTO POLICY (POLICYNUMBER, CUSTOMERNUMBER, ISSUEDATE, EXPIRYDATE, POLICYTYPE, LASTCHANGED,
                                BROKERID, BROKERSREFERENCE, PAYMENT, COMMISSION)
            VALUES (?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Resource seedFile;
    private final Resource db2CreJcl;
    private final Map<String, PolicySeeder> seedersByType;

    public SeedDataLoader(JdbcTemplate jdbcTemplate,
                          @Value("${genapp.seed.location}") Resource seedFile,
                          @Value("${genapp.seed.db2cre-location}") Resource db2CreJcl,
                          List<PolicySeeder> seeders) {
        this.jdbcTemplate = jdbcTemplate;
        this.seedFile = seedFile;
        this.db2CreJcl = db2CreJcl;
        this.seedersByType = seeders.stream()
                .collect(Collectors.toUnmodifiableMap(PolicySeeder::policyType, Function.identity()));
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Db2CreInserts jcl = Db2CreInserts.parse(db2CreJcl.getContentAsString(StandardCharsets.ISO_8859_1));
        for (KsdsPolyRecord record : readSeedFile(seedFile)) {
            insertPolicy(record, jcl);
            PolicySeeder seeder = seedersByType.get(record.policyType());
            if (seeder != null) {
                seeder.seed(record, jcl, jdbcTemplate);
            }
        }
    }

    static List<KsdsPolyRecord> readSeedFile(Resource file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.ISO_8859_1))) {
            return reader.lines().filter(line -> !line.isBlank()).map(KsdsPolyRecord::parse).toList();
        }
    }

    private void insertPolicy(KsdsPolyRecord r, Db2CreInserts jcl) {
        Map<String, String> p = jcl.findByPolicyNumber("policy", r.policyNumber()).orElse(Map.of());
        jdbcTemplate.update(INSERT_POLICY,
                r.policyNumber(),
                r.customerNumber(),
                date(p.get("issuedate")),
                date(p.get("expirydate")),
                r.policyType(),
                timestamp(p.get("lastchanged")),
                integer(p.get("brokerid")),
                p.get("brokersreference"),
                integer(p.get("payment")),
                integer(p.get("commission")));
    }

    /** SQL DATE literal {@code 'yyyy-MM-dd'}. */
    public static Date date(String literal) {
        return literal == null || literal.isBlank() ? null : Date.valueOf(literal.trim());
    }

    /** SQL TIMESTAMP literal {@code 'yyyy-MM-dd HH:mm:ss[.f]'}. */
    public static Timestamp timestamp(String literal) {
        return literal == null || literal.isBlank() ? null : Timestamp.valueOf(literal.trim());
    }

    /** SQL integer literal. */
    public static Integer integer(String literal) {
        return literal == null || literal.isBlank() ? null : Integer.valueOf(literal.trim());
    }
}
