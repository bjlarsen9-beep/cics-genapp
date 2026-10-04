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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Parity tests for LGIPDB01 {@code GET-Commercial-DB2-INFO-1} (01ICOM). Every type-C record of
 * base/data/ksdspoly.txt is read here with the LGAPVS01 {@code WF-C-Policy-Data} offsets (independently of the
 * service's KsdsPolyRecord); the remaining fields are checked against the db2cre.jcl COMMERCIAL INSERTs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommercialPolicyInquiryParityTest {

    private static final Path BASE = Path.of(System.getProperty("genapp.base.dir", "../../base"));
    private static final DateTimeFormatter DB2_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * One ksdspoly.txt record: WF-Request-ID X(1), WF-Customer-Num X(10), WF-Policy-Num X(10), then
     * WF-C-Policy-Data: WF-B-Postcode X(8), WF-B-Status 9(4), WF-B-Customer X(31).
     */
    record CommercialRecord(String line) {
        String customerNumber() {
            return line.substring(1, 11);
        }

        String policyNumber() {
            return line.substring(11, 21);
        }

        String postcode() {
            return line.substring(21, 29).stripTrailing();
        }

        String rawStatus() {
            return line.substring(29, 33);
        }

        String customer() {
            return line.substring(33, 64).stripTrailing();
        }

        @Override
        public String toString() {
            return "policy " + Long.parseLong(policyNumber());
        }
    }

    static Stream<CommercialRecord> commercialRecords() throws IOException {
        return Files.readAllLines(BASE.resolve("data/ksdspoly.txt"), StandardCharsets.ISO_8859_1).stream()
                .filter(l -> l.startsWith("C"))
                .map(CommercialRecord::new);
    }

    static Map<String, String> jclCommercial(String policyNumber) throws IOException {
        return Db2CreInserts.parse(Files.readString(BASE.resolve("cntl/db2cre.jcl"), StandardCharsets.ISO_8859_1))
                .findByPolicyNumber("commercial", Long.parseLong(policyNumber)).orElseThrow();
    }

    private static boolean isNumeric(String s) {
        return s.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    private static String db2Timestamp(String sqlLiteral) {
        return LocalDateTime.parse(sqlLiteral.replace(' ', 'T')).format(DB2_TIMESTAMP);
    }

    @Test
    void sampleFileHasCommercialPolicies9And10ForCustomers5And1() throws IOException {
        List<CommercialRecord> records = commercialRecords().toList();
        assertThat(records).extracting(r -> r.policyNumber() + "/" + r.customerNumber())
                .containsExactlyInAnyOrder("0000000010/0000000001", "0000000009/0000000005");
        assertThat(records).allSatisfy(r -> assertThat(r.line()).hasSize(64));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commercialRecords")
    void returnsEveryCommercialFieldForSampleRecord(CommercialRecord r) throws Exception {
        Map<String, String> c = jclCommercial(r.policyNumber());
        // Summary fields: the sample file wins; a non-NUMERIC WF-B-Status falls back to the Db2 value.
        int expectedStatus = isNumeric(r.rawStatus()) ? Integer.parseInt(r.rawStatus()) : Integer.parseInt(c.get("status"));
        ResultActions result = mvc.perform(get("/policies/{c}/{p}", r.customerNumber(), r.policyNumber()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.customerNumber").value(r.customerNumber()))
                .andExpect(jsonPath("$.policyNumber").value(r.policyNumber()))
                .andExpect(jsonPath("$.policyType").value("C"))
                .andExpect(jsonPath("$.details.length()").value(16))
                .andExpect(jsonPath("$.details.postcode").value(r.postcode()))
                .andExpect(jsonPath("$.details.status").value(expectedStatus))
                .andExpect(jsonPath("$.details.customer").value(r.customer()))
                // CA-POLICY-COMMON comes from COMMERCIAL, not POLICY (INTO :DB2-LASTCHANGED, :DB2-ISSUEDATE, ...).
                .andExpect(jsonPath("$.common.length()").value(6))
                .andExpect(jsonPath("$.common.issueDate").value(c.get("startdate")))
                .andExpect(jsonPath("$.common.expiryDate").value(c.get("renewaldate")))
                .andExpect(jsonPath("$.common.lastChanged").value(db2Timestamp(c.get("requestdate"))))
                .andExpect(jsonPath("$.common.brokerId").value(0))
                .andExpect(jsonPath("$.common.brokersReference").value(""))
                .andExpect(jsonPath("$.common.payment").value(0));
        Map<String, String> textColumns = Map.of(
                "address", "address", "latitude", "latituden", "longitude", "longitudew",
                "propertyType", "propertytype", "rejectReason", "rejectionreason");
        for (var e : textColumns.entrySet()) {
            result.andExpect(jsonPath("$.details." + e.getKey()).value(c.get(e.getValue())));
        }
        Map<String, String> numberColumns = Map.of(
                "firePeril", "fireperil", "firePremium", "firepremium", "crimePeril", "crimeperil",
                "crimePremium", "crimepremium", "floodPeril", "floodperil", "floodPremium", "floodpremium",
                "weatherPeril", "weatherperil", "weatherPremium", "weatherpremium");
        for (var e : numberColumns.entrySet()) {
            result.andExpect(jsonPath("$.details." + e.getKey()).value(Integer.parseInt(c.get(e.getValue()))));
        }
    }

    @Test
    void policy10MatchesHandCheckedValuesWithAndWithoutLeadingZeros() throws Exception {
        for (String[] path : new String[][] {{"1", "10"}, {"0000000001", "0000000010"}}) {
            mvc.perform(get("/policies/{c}/{p}", path[0], path[1]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerNumber").value("0000000001"))
                    .andExpect(jsonPath("$.policyNumber").value("0000000010"))
                    .andExpect(jsonPath("$.common.issueDate").value("2011-08-25"))
                    .andExpect(jsonPath("$.common.expiryDate").value("2011-08-24"))
                    .andExpect(jsonPath("$.common.lastChanged").value("2011-08-23-10.44.34.000000"))
                    .andExpect(jsonPath("$.details.address").value("HURSLEY PARK"))
                    .andExpect(jsonPath("$.details.postcode").value("SO212JN"))
                    .andExpect(jsonPath("$.details.latitude").value("51.026701"))
                    .andExpect(jsonPath("$.details.longitude").value("-1.398903"))
                    .andExpect(jsonPath("$.details.customer").value("IBM"))
                    .andExpect(jsonPath("$.details.propertyType").value("OFFICES"))
                    .andExpect(jsonPath("$.details.firePeril").value(3))
                    .andExpect(jsonPath("$.details.firePremium").value(8000))
                    .andExpect(jsonPath("$.details.weatherPremium").value(5000))
                    .andExpect(jsonPath("$.details.status").value(1))
                    .andExpect(jsonPath("$.details.rejectReason").value("ACTIVE"));
        }
    }

    @Test
    void policy9UsesSampleFileCustomerOverDb2CreAndKeepsDb2StatusForNonNumericFileStatus() throws Exception {
        CommercialRecord r = commercialRecords().filter(x -> x.policyNumber().equals("0000000009")).findFirst()
                .orElseThrow();
        assertThat(r.customer()).isEqualTo("Clarets Merchandise");
        assertThat(jclCommercial("9")).containsEntry("customer", "Burnley Football Club").containsEntry("status", "1");
        assertThat(isNumeric(r.rawStatus())).as("WF-B-Status %s", r.rawStatus()).isFalse();
        mvc.perform(get("/policies/5/0000000009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.customer").value("Clarets Merchandise"))
                .andExpect(jsonPath("$.details.status").value(1));
    }

    @Test
    void commonDatesComeFromCommercialTableNotPolicyTable() throws Exception {
        Map<String, Object> p = jdbc.queryForMap(
                "SELECT CAST(ISSUEDATE AS VARCHAR) I, CAST(EXPIRYDATE AS VARCHAR) E FROM POLICY WHERE POLICYNUMBER = 10");
        assertThat(p).containsEntry("I", "2011-08-22").containsEntry("E", "2012-08-21");
        mvc.perform(get("/policies/1/10"))
                .andExpect(jsonPath("$.common.issueDate").value("2011-08-25"))
                .andExpect(jsonPath("$.common.expiryDate").value("2011-08-24"));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
        "5, 10",                       // Commercial policy 10 belongs to customer 1
        "0000000001, 0000000009",      // Commercial policy 9 belongs to customer 5
        "1, 11",                       // customer 1 exists, policy 11 does not
        "0000000005, 0000000099",
        "1, 0"
    })
    void wrongCustomerOrUnknownPolicyReturns404WithReturnCode01(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
        "abc, 10",
        "1, 1O",
        "-1, 10",
        "1, -10",
        "12345678901, 10",
        "1, 00000000010",
        "1, 1.0",
        "+1, 10"
    })
    void malformedNumbersReturn400WithReturnCode98(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }

    @Test
    void nullColumnWithoutIndicatorVariableReturns500WithReturnCode90() throws Exception {
        String original = jdbc.queryForObject("SELECT REJECTIONREASON FROM COMMERCIAL WHERE POLICYNUMBER = 10",
                String.class);
        jdbc.update("UPDATE COMMERCIAL SET REJECTIONREASON = NULL WHERE POLICYNUMBER = 10");
        try {
            mvc.perform(get("/policies/1/10"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.returnCode").value("90"));
        } finally {
            jdbc.update("UPDATE COMMERCIAL SET REJECTIONREASON = ? WHERE POLICYNUMBER = 10", original);
        }
    }

    @Test
    void signedAndOversizedNumbersAreMovedIntoUnsignedPicFieldsLikeCobol() throws Exception {
        Map<String, Object> original = jdbc.queryForMap(
                "SELECT FIREPERIL, FIREPREMIUM, STATUS FROM COMMERCIAL WHERE POLICYNUMBER = 10");
        jdbc.update("UPDATE COMMERCIAL SET FIREPERIL = -3, FIREPREMIUM = 123456789, STATUS = 32767 WHERE POLICYNUMBER = 10");
        try {
            mvc.perform(get("/policies/1/10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.details.firePeril").value(3))
                    .andExpect(jsonPath("$.details.firePremium").value(23456789))
                    .andExpect(jsonPath("$.details.status").value(2767));
        } finally {
            jdbc.update("UPDATE COMMERCIAL SET FIREPERIL = ?, FIREPREMIUM = ?, STATUS = ? WHERE POLICYNUMBER = 10",
                    original.get("FIREPERIL"), original.get("FIREPREMIUM"), original.get("STATUS"));
        }
    }
}
