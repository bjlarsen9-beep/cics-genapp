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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity tests for LGIPDB01 {@code GET-ENDOW-DB2-INFO} (request ID 01IEND). Every type-E record of
 * base/data/ksdspoly.txt, read here with LGAPVS01's {@code WF-E-Policy-Data} layout (independently of
 * {@link KsdsPolyRecord}), must come back field for field; columns the file lacks are checked against the
 * {@code policy} and {@code endowment} INSERTs in base/cntl/db2cre.jcl.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndowmentPolicyInquiryParityTest {

    private static final Path BASE = Path.of(System.getProperty("genapp.base.dir", "../../base"));
    private static final Path KSDSPOLY = BASE.resolve("data/ksdspoly.txt");
    private static final Path DB2CRE = BASE.resolve("cntl/db2cre.jcl");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** LGAPVS01 field name, JSON property under {@code details}, offset in the 64-byte record, PIC X length. */
    private record Field(String cobolName, String json, int offset, int length) {
    }

    /** {@code WF-Request-ID X(1)}, {@code WF-Customer-Num X(10)}, {@code WF-Policy-Num X(10)}, then: */
    private static final List<Field> SUMMARY_FIELDS = List.of(
            new Field("WF-E-WITH-PROFITS", "withProfits", 21, 1),
            new Field("WF-E-EQUITIES", "equities", 22, 1),
            new Field("WF-E-MANAGED-FUND", "managedFund", 23, 1),
            new Field("WF-E-FUND-NAME", "fundName", 24, 10),
            new Field("WF-E-LIFE-ASSURED", "lifeAssured", 34, 30));

    private Db2CreInserts jcl;

    Stream<String> endowmentRecords() throws IOException {
        return Files.readAllLines(KSDSPOLY, StandardCharsets.ISO_8859_1).stream()
                .filter(l -> !l.isBlank())
                .filter(l -> l.startsWith("E"));
    }

    private Db2CreInserts jcl() throws IOException {
        if (jcl == null) {
            jcl = Db2CreInserts.parse(Files.readString(DB2CRE, StandardCharsets.ISO_8859_1));
        }
        return jcl;
    }

    /** SQL TIMESTAMP literal {@code yyyy-MM-dd HH:mm:ss} as Db2 returns it into {@code PIC X(26)}. */
    private static String db2Timestamp(String literal) {
        return literal.replace(' ', '-').replace(':', '.') + ".000000";
    }

    @Test
    void sampleFileHasTwoEndowmentRecordsForPolicies4And5() throws IOException {
        assertThat(endowmentRecords().map(r -> r.substring(1, 21)).toList())
                .containsExactlyInAnyOrder("00000000030000000005", "00000000080000000004");
        assertThat(endowmentRecords()).allSatisfy(r -> assertThat(r).hasSize(64));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endowmentRecords")
    void returnsEveryEndowmentFieldForSampleRecord(String record) throws Exception {
        String customerNumber = record.substring(1, 11);
        String policyNumber = record.substring(11, 21);
        long policy = Long.parseLong(policyNumber);
        Map<String, String> p = jcl().findByPolicyNumber("policy", policy).orElseThrow();
        Map<String, String> e = jcl().findByPolicyNumber("endowment", policy).orElseThrow();

        var result = mvc.perform(get("/policies/{c}/{p}", customerNumber, policyNumber))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.customerNumber").value(customerNumber))
                .andExpect(jsonPath("$.policyNumber").value(policyNumber))
                .andExpect(jsonPath("$.policyType").value("E"))
                .andExpect(jsonPath("$.common.length()").value(6))
                .andExpect(jsonPath("$.common.issueDate").value(p.get("issuedate")))
                .andExpect(jsonPath("$.common.expiryDate").value(p.get("expirydate")))
                .andExpect(jsonPath("$.common.lastChanged").value(db2Timestamp(p.get("lastchanged"))))
                .andExpect(jsonPath("$.common.brokerId").value(Long.parseLong(p.get("brokerid"))))
                .andExpect(jsonPath("$.common.brokersReference").value(p.get("brokersreference").stripTrailing()))
                .andExpect(jsonPath("$.common.payment").value(Long.parseLong(p.get("payment"))))
                .andExpect(jsonPath("$.details.length()").value(8))
                .andExpect(jsonPath("$.details.term").value(Integer.parseInt(e.get("term"))))
                .andExpect(jsonPath("$.details.sumAssured").value(Integer.parseInt(e.get("sumassured"))))
                .andExpect(jsonPath("$.details.paddingData").value(""));
        for (Field f : SUMMARY_FIELDS) {
            String expected = record.substring(f.offset(), f.offset() + f.length()).stripTrailing();
            result.andExpect(jsonPath("$.details." + f.json()).value(expected));
        }
    }

    @Test
    void summaryFieldsAgreeWithDb2CreInserts() throws IOException {
        for (String record : endowmentRecords().toList()) {
            Map<String, String> e = jcl().findByPolicyNumber("endowment", Long.parseLong(record.substring(11, 21)))
                    .orElseThrow();
            for (Field f : SUMMARY_FIELDS) {
                String fromFile = record.substring(f.offset(), f.offset() + f.length()).stripTrailing();
                assertThat(e.get(f.json().toLowerCase())).as(f.cobolName()).isEqualTo(fromFile);
            }
        }
    }

    @Test
    void policy4MatchesHandCheckedValues() throws Exception {
        Map<String, Object> expected = Map.ofEntries(
                Map.entry("$.customerNumber", "0000000008"),
                Map.entry("$.policyNumber", "0000000004"),
                Map.entry("$.policyType", "E"),
                Map.entry("$.common.issueDate", "2011-08-05"),
                Map.entry("$.common.expiryDate", "2012-08-04"),
                Map.entry("$.common.lastChanged", "2011-08-05-10.45.19.000000"),
                Map.entry("$.common.brokerId", 0),
                Map.entry("$.common.brokersReference", ""),
                Map.entry("$.common.payment", 0),
                Map.entry("$.details.withProfits", "Y"),
                Map.entry("$.details.equities", "Y"),
                Map.entry("$.details.managedFund", "N"),
                Map.entry("$.details.fundName", "LIONTMR"),
                Map.entry("$.details.term", 5),
                Map.entry("$.details.sumAssured", 12500),
                Map.entry("$.details.lifeAssured", "J. MORRIS"),
                Map.entry("$.details.paddingData", ""));
        var result = mvc.perform(get("/policies/8/4")).andExpect(status().isOk());
        for (var e : expected.entrySet()) {
            result.andExpect(jsonPath(e.getKey()).value(e.getValue()));
        }
    }

    @ParameterizedTest
    @CsvSource({"3, 5", "0000000003, 0000000005", "3, 0000000005", "0000000003, 5"})
    void acceptsUnpaddedAndZeroPaddedNumbers(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerNumber").value("0000000003"))
                .andExpect(jsonPath("$.policyNumber").value("0000000005"))
                .andExpect(jsonPath("$.details.fundName").value("SHEPPA"));
    }

    @ParameterizedTest
    @CsvSource({
            "3, 4",                       // policy 4 is an endowment of customer 8
            "8, 5",                       // policy 5 is an endowment of customer 3
            "0000000001, 0000000004",
            "8, 11",                      // customer 8 exists, no policy 11
            "3, 0000000099"})
    void wrongCustomerOrUnknownPolicyReturns404WithReturnCode01(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "4a", "-4", "-0000000004", "4.0", "12345678901", "00000000004"})
    void malformedPolicyNumberReturns400WithReturnCode98(String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", "8", policy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "8x", "-8", "-0000000008", "12345678901", "00000000008"})
    void malformedCustomerNumberReturns400WithReturnCode98(String customer) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }

    // Branches the sample data cannot reach; each test changes the seeded rows inside a rolled-back transaction.

    @Test
    @Transactional
    void policyRowWithoutEndowmentRowReturns404WithReturnCode01() throws Exception {
        jdbc.update("DELETE FROM ENDOWMENT WHERE POLICYNUMBER = 4");
        mvc.perform(get("/policies/8/4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @Test
    @Transactional
    void nullBrokerIdPaymentAndPaddingDataUseInitializedDefaults() throws Exception {
        jdbc.update("UPDATE POLICY SET BROKERID = NULL, BROKERSREFERENCE = NULL, PAYMENT = NULL WHERE POLICYNUMBER = 4");
        mvc.perform(get("/policies/8/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.common.brokerId").value(0))
                .andExpect(jsonPath("$.common.brokersReference").value(""))
                .andExpect(jsonPath("$.common.payment").value(0))
                .andExpect(jsonPath("$.details.paddingData").value(""));
    }

    @Test
    @Transactional
    void nonNullPaddingDataIsReturnedAsStored() throws Exception {
        jdbc.update("UPDATE ENDOWMENT SET PADDINGDATA = '  extra data  ' WHERE POLICYNUMBER = 4");
        mvc.perform(get("/policies/8/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.paddingData").value("  extra data  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHPROFITS", "EQUITIES", "MANAGEDFUND", "FUNDNAME", "TERM", "SUMASSURED", "LIFEASSURED"})
    @Transactional
    void nullInColumnWithoutIndicatorReturns500WithReturnCode90(String column) throws Exception {
        jdbc.update("UPDATE ENDOWMENT SET " + column + " = NULL WHERE POLICYNUMBER = 4");
        mvc.perform(get("/policies/8/4"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.returnCode").value("90"));
    }

    @ParameterizedTest
    @CsvSource({"123, 1234567, 23, 234567", "-7, -50000, 7, 50000", "99, 999999, 99, 999999"})
    @Transactional
    void termAndSumAssuredAreTruncatedLikeCobolMoveToUnsignedPic(int term, int sumAssured,
                                                                 int expectedTerm, int expectedSum) throws Exception {
        jdbc.update("UPDATE ENDOWMENT SET TERM = ?, SUMASSURED = ? WHERE POLICYNUMBER = 4", term, sumAssured);
        mvc.perform(get("/policies/8/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.term").value(expectedTerm))
                .andExpect(jsonPath("$.details.sumAssured").value(expectedSum));
    }
}
