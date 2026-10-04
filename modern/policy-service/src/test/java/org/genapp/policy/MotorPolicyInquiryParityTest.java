package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity tests for LGIPDB01 {@code GET-MOTOR-DB2-INFO}: every type-M record of base/data/ksdspoly.txt, read here with
 * LGAPVS01's {@code WF-M-Policy-Data} offsets (independently of {@link KsdsPolyRecord}), must come back field for
 * field; the columns the file lacks must match the {@code policy} and {@code motor} INSERTs in db2cre.jcl.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MotorPolicyInquiryParityTest {

    private static final Path KSDSPOLY = Path.of(System.getProperty("genapp.base.dir", "../../base"),
            "data", "ksdspoly.txt");

    private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DB2_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * One LGAPVS01 {@code WF-Policy-Info} record: {@code WF-Request-ID X(1)}, {@code WF-Customer-Num X(10)},
     * {@code WF-Policy-Num X(10)}, then {@code WF-M-Policy-Data}: {@code WF-M-MAKE X(15)}, {@code WF-M-MODEL X(15)},
     * {@code WF-M-VALUE 9(6)}, {@code WF-M-REGNUMBER X(7)}.
     */
    record MotorRecord(String customerNumber, String policyNumber, String make, String model, long value,
                       String regNumber) {

        static MotorRecord parse(String line) {
            return new MotorRecord(
                    line.substring(1, 11),
                    line.substring(11, 21),
                    line.substring(21, 36).stripTrailing(),
                    line.substring(36, 51).stripTrailing(),
                    Long.parseLong(line.substring(51, 57)),
                    line.substring(57, 64).stripTrailing());
        }

        @Override
        public String toString() {
            return "policy " + Long.parseLong(policyNumber);
        }
    }

    static Stream<MotorRecord> motorRecords() throws IOException {
        return Files.readAllLines(KSDSPOLY, StandardCharsets.ISO_8859_1).stream()
                .filter(l -> l.startsWith("M"))
                .map(MotorRecord::parse);
    }

    @Test
    void sampleFileHasThreeMotorPolicies() throws IOException {
        assertThat(motorRecords().map(r -> r.customerNumber() + "/" + r.policyNumber()))
                .containsExactly("0000000002/0000000001", "0000000005/0000000003", "0000000010/0000000002");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("motorRecords")
    void returnsEveryMotorFieldOfTheSampleRecordAndDb2CreInserts(MotorRecord r) throws Exception {
        Db2CreInserts jcl = PolicySeedDataTest.db2cre();
        long policyNumber = Long.parseLong(r.policyNumber());
        Map<String, String> p = jcl.findByPolicyNumber("policy", policyNumber).orElseThrow();
        Map<String, String> m = jcl.findByPolicyNumber("motor", policyNumber).orElseThrow();

        ResultActions result = mvc.perform(get("/policies/{c}/{p}", r.customerNumber(), r.policyNumber()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.customerNumber").value(r.customerNumber()))
                .andExpect(jsonPath("$.policyNumber").value(r.policyNumber()))
                .andExpect(jsonPath("$.policyType").value("M"))
                // ksdspoly.txt summary (WF-M-Policy-Data)
                .andExpect(jsonPath("$.details.length()").value(9))
                .andExpect(jsonPath("$.details.make").value(r.make()))
                .andExpect(jsonPath("$.details.model").value(r.model()))
                .andExpect(jsonPath("$.details.value").value(r.value()))
                .andExpect(jsonPath("$.details.regNumber").value(r.regNumber()))
                // db2cre.jcl motor INSERT
                .andExpect(jsonPath("$.details.colour").value(m.get("colour")))
                .andExpect(jsonPath("$.details.cc").value(Integer.parseInt(m.get("cc"))))
                .andExpect(jsonPath("$.details.manufactured").value(m.get("yearofmanufacture")))
                .andExpect(jsonPath("$.details.premium").value(Long.parseLong(m.get("premium"))))
                .andExpect(jsonPath("$.details.accidents").value(Long.parseLong(m.get("accidents"))))
                // db2cre.jcl policy INSERT
                .andExpect(jsonPath("$.common.length()").value(6))
                .andExpect(jsonPath("$.common.issueDate").value(p.get("issuedate")))
                .andExpect(jsonPath("$.common.expiryDate").value(p.get("expirydate")))
                .andExpect(jsonPath("$.common.lastChanged").value(
                        DB2_TIMESTAMP.format(LocalDateTime.parse(p.get("lastchanged"), SQL_TIMESTAMP))))
                .andExpect(jsonPath("$.common.brokerId").value(Long.parseLong(p.get("brokerid"))))
                .andExpect(jsonPath("$.common.brokersReference").value(p.get("brokersreference")))
                .andExpect(jsonPath("$.common.payment").value(Long.parseLong(p.get("payment"))));
        assertThat(m.get("make")).isEqualTo(r.make());
        assertThat(m.get("model")).isEqualTo(r.model());
        assertThat(m.get("regnumber")).isEqualTo(r.regNumber());
    }

    @Test
    void policyOneMatchesHandCheckedValuesWithTheFileValueWinning() throws Exception {
        Map<String, Object> expected = Map.ofEntries(
                Map.entry("$.customerNumber", "0000000002"),
                Map.entry("$.policyNumber", "0000000001"),
                Map.entry("$.policyType", "M"),
                Map.entry("$.common.issueDate", "2011-05-11"),
                Map.entry("$.common.expiryDate", "2012-05-10"),
                Map.entry("$.common.lastChanged", "2011-05-11-17.12.17.000000"),
                Map.entry("$.common.brokerId", 0),
                Map.entry("$.common.brokersReference", ""),
                Map.entry("$.common.payment", 0),
                Map.entry("$.details.make", "FORD"),
                Map.entry("$.details.model", "KA"),
                Map.entry("$.details.value", 85000),
                Map.entry("$.details.regNumber", "LL60LOO"),
                Map.entry("$.details.colour", "ORANGE"),
                Map.entry("$.details.cc", 1000),
                Map.entry("$.details.manufactured", "1999-07-19"),
                Map.entry("$.details.premium", 450),
                Map.entry("$.details.accidents", 0));
        ResultActions result = mvc.perform(get("/policies/2/1")).andExpect(status().isOk());
        for (var e : expected.entrySet()) {
            result.andExpect(jsonPath(e.getKey()).value(e.getValue()));
        }
    }

    @Test
    void sampleFileAndDb2CreDisagreeOnPolicyOneValue() throws IOException {
        MotorRecord fileRecord = motorRecords().filter(r -> r.policyNumber().equals("0000000001")).findFirst()
                .orElseThrow();
        assertThat(fileRecord.value()).isEqualTo(85000);
        assertThat(PolicySeedDataTest.db2cre().findByPolicyNumber("motor", 1).orElseThrow())
                .containsEntry("value", "8500");
    }

    @ParameterizedTest
    @CsvSource({"10, 2", "0000000010, 0000000002", "10, 0000000002", "0000000010, 2"})
    void acceptsUnpaddedAndZeroPaddedNumbers(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerNumber").value("0000000010"))
                .andExpect(jsonPath("$.policyNumber").value("0000000002"))
                .andExpect(jsonPath("$.details.make").value("VOLKSWAGEN"));
    }

    @ParameterizedTest
    @CsvSource({
            "5, 1",            // Motor policy 1 belongs to customer 2
            "0000000002, 0000000003", // Motor policy 3 belongs to customer 5
            "1, 2",            // Motor policy 2 belongs to customer 10
            "2, 11",           // customer 2 exists, no policy 11
            "0000000005, 0000000099"})
    void wrongCustomerOrUnknownPolicyReturns404WithReturnCode01(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @ParameterizedTest
    @CsvSource({
            "abc, 1", "2, M1", "-2, 1", "2, -1", "12345678901, 1", "2, 00000000001", "2, 1.0", "+2, 1"})
    void malformedNumbersReturn400WithReturnCode98(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }

    private void insertMotorPolicy(long policyNumber, String colour, Integer value, Integer cc,
                                   Integer brokerId) {
        jdbc.update("""
                INSERT INTO POLICY (POLICYNUMBER, CUSTOMERNUMBER, ISSUEDATE, EXPIRYDATE, POLICYTYPE, BROKERID,
                                    BROKERSREFERENCE, PAYMENT, COMMISSION)
                VALUES (?, 2, DATE '2020-01-01', DATE '2021-01-01', 'M', ?, NULL, NULL, 0)""", policyNumber, brokerId);
        jdbc.update("""
                INSERT INTO MOTOR (POLICYNUMBER, MAKE, MODEL, "VALUE", REGNUMBER, COLOUR, CC, YEAROFMANUFACTURE,
                                   PREMIUM, ACCIDENTS)
                VALUES (?, 'TESTMAKE', 'TESTMODEL', ?, 'TEST1', ?, ?, DATE '2000-01-01', 1, 0)""",
                policyNumber, value, colour, cc);
    }

    @Test
    @Transactional
    void nullInIndicatorColumnsGivesInitializedDefaults() throws Exception {
        insertMotorPolicy(900001, "BLUE", 1, 1, null);
        mvc.perform(get("/policies/2/900001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.common.brokerId").value(0))
                .andExpect(jsonPath("$.common.brokersReference").value(""))
                .andExpect(jsonPath("$.common.payment").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"colour", "value", "cc"})
    @Transactional
    void nullInMotorColumnWithoutIndicatorReturns500WithReturnCode90(String column) throws Exception {
        insertMotorPolicy(900002,
                column.equals("colour") ? null : "BLUE",
                column.equals("value") ? null : 1,
                column.equals("cc") ? null : 1,
                0);
        mvc.perform(get("/policies/2/900002"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.returnCode").value("90"));
    }

    @Test
    @Transactional
    void numericMovesDropSignAndHighOrderDigitsLikeCobol() throws Exception {
        insertMotorPolicy(900003, "BLUE", 1234567, -1600, 0);
        mvc.perform(get("/policies/2/900003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.value").value(234567))
                .andExpect(jsonPath("$.details.cc").value(1600));
        assertThat(List.of(MotorPolicyInquiry.toUnsigned(-5, 4), MotorPolicyInquiry.toUnsigned(10000, 4)))
                .containsExactly(5L, 0L);
    }
}
