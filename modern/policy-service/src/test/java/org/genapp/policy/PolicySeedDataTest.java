package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The shared POLICY rows loaded from base/data/ksdspoly.txt (keys) and base/cntl/db2cre.jcl (other columns). */
@SpringBootTest
class PolicySeedDataTest {

    static final Path BASE = Path.of(System.getProperty("genapp.base.dir", "../../base"));
    static final Path KSDSPOLY = BASE.resolve("data/ksdspoly.txt");
    static final Path DB2CRE = BASE.resolve("cntl/db2cre.jcl");

    @Autowired
    private JdbcTemplate jdbc;

    static List<String> ksdspolyLines() throws IOException {
        return Files.readAllLines(KSDSPOLY, StandardCharsets.ISO_8859_1).stream().filter(l -> !l.isBlank()).toList();
    }

    static Db2CreInserts db2cre() throws IOException {
        return Db2CreInserts.parse(Files.readString(DB2CRE, StandardCharsets.ISO_8859_1));
    }

    @Test
    void sampleFileHasTenFixedWidthRecords() throws IOException {
        assertThat(ksdspolyLines()).hasSize(10).allSatisfy(r -> assertThat(r).hasSize(KsdsPolyRecord.LRECL));
    }

    @Test
    void everySampleRecordHasAPolicyRowWithTheSameKeyAndType() throws IOException {
        List<String> lines = ksdspolyLines();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM POLICY", Integer.class)).isEqualTo(lines.size());
        for (String line : lines) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT CUSTOMERNUMBER, POLICYTYPE FROM POLICY WHERE POLICYNUMBER = ?",
                    Long.parseLong(line.substring(11, 21)));
            assertThat(((Number) row.get("CUSTOMERNUMBER")).longValue()).isEqualTo(Long.parseLong(line.substring(1, 11)));
            assertThat(row.get("POLICYTYPE")).isEqualTo(line.substring(0, 1));
        }
    }

    @Test
    void policyColumnsNotInTheSampleFileComeFromDb2Cre() throws IOException {
        Db2CreInserts jcl = db2cre();
        for (Map<String, String> p : jcl.rows("policy")) {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT CAST(ISSUEDATE AS VARCHAR) I, CAST(EXPIRYDATE AS VARCHAR) E,
                           CAST(LASTCHANGED AS VARCHAR) L, BROKERID, BROKERSREFERENCE, PAYMENT, COMMISSION
                    FROM POLICY WHERE POLICYNUMBER = ?""", Long.parseLong(p.get("policynumber")));
            assertThat(row.get("I")).isEqualTo(p.get("issuedate"));
            assertThat(row.get("E")).isEqualTo(p.get("expirydate"));
            assertThat(row.get("L")).isEqualTo(p.get("lastchanged"));
            assertThat(row.get("BROKERID").toString()).isEqualTo(p.get("brokerid"));
            assertThat(row.get("BROKERSREFERENCE").toString().strip()).isEqualTo(p.get("brokersreference"));
            assertThat(row.get("PAYMENT").toString()).isEqualTo(p.get("payment"));
            assertThat(row.get("COMMISSION").toString()).isEqualTo(p.get("commission"));
        }
    }

    @Test
    void db2CreParserReadsEverySampleInsert() throws IOException {
        Db2CreInserts jcl = db2cre();
        assertThat(jcl.rows("customer")).hasSize(10);
        assertThat(jcl.rows("policy")).hasSize(10);
        assertThat(jcl.rows("endowment")).hasSize(2);
        assertThat(jcl.rows("house")).hasSize(3);
        assertThat(jcl.rows("motor")).hasSize(3);
        assertThat(jcl.rows("commercial")).hasSize(2);
        assertThat(jcl.findByPolicyNumber("house", 6).orElseThrow())
                .containsEntry("housenumber", "   5")
                .containsEntry("housename", " ");
        assertThat(jcl.findByPolicyNumber("policy", 10).orElseThrow())
                .containsEntry("customernumber", "1")
                .containsEntry("brokersreference", "")
                .containsEntry("lastchanged", "2011-08-22 12:13:01");
    }

    @Test
    void ksdsPolyRecordSplitsKeyAndData() {
        KsdsPolyRecord r = KsdsPolyRecord.parse("M00000000020000000001FORD           KA             085000LL60LOO");
        assertThat(r.policyType()).isEqualTo("M");
        assertThat(r.customerNumber()).isEqualTo(2);
        assertThat(r.policyNumber()).isEqualTo(1);
        assertThat(r.data()).hasSize(KsdsPolyRecord.DATA_LENGTH);
        assertThat(r.text(0, 15)).isEqualTo("FORD");
        assertThat(r.raw(30, 6)).isEqualTo("085000");
    }
}
