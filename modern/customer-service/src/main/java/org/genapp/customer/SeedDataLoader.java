package org.genapp.customer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Loads the GenApp sample customers from {@code base/data/ksdscust.txt} into the local H2 CUSTOMER table. */
@Component
@ConditionalOnProperty(name = "genapp.seed.enabled", havingValue = "true")
public class SeedDataLoader implements ApplicationRunner {

    private static final String INSERT = """
            INSERT INTO CUSTOMER (CUSTOMERNUMBER, FIRSTNAME, LASTNAME, DATEOFBIRTH, HOUSENAME,
                                  HOUSENUMBER, POSTCODE, PHONEHOME, PHONEMOBILE, EMAILADDRESS)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Resource seedFile;

    public SeedDataLoader(JdbcTemplate jdbcTemplate, @Value("${genapp.seed.location}") Resource seedFile) {
        this.jdbcTemplate = jdbcTemplate;
        this.seedFile = seedFile;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(seedFile.getInputStream(), StandardCharsets.ISO_8859_1))) {
            reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(KsdsCustRecord::parse)
                    .forEach(this::insert);
        }
    }

    private void insert(KsdsCustRecord r) {
        jdbcTemplate.update(INSERT,
                r.customerNumber(),
                r.firstName(),
                r.lastName(),
                r.dateOfBirth().isEmpty() ? null : Date.valueOf(LocalDate.parse(r.dateOfBirth())),
                r.houseName(),
                r.houseNumber(),
                r.postcode(),
                r.phoneHome(),
                r.phoneMobile(),
                r.emailAddress());
    }
}
