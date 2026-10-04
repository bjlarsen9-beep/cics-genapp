package org.genapp.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity tests for LGIPDB01 {@code GET-HOUSE-DB2-INFO}: every House record in base/data/ksdspoly.txt, read here
 * with LGAPVS01's {@code WF-Policy-Info} / {@code WF-H-Policy-Data} offsets (independently of
 * {@link KsdsPolyRecord}), must come back field for field; columns the file lacks must match the db2cre.jcl
 * INSERTs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HousePolicyInquiryParityTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** One {@code ksdspoly.txt} House record split with the LGAPVS01 layout. */
    record HouseRecord(String customerNumber, String policyNumber, String propertyType, String bedrooms,
                       String value, String postcode, String houseName) {

        static HouseRecord parse(String line) {
            // WF-Request-ID X(1), WF-Customer-Num X(10), WF-Policy-Num X(10), WF-H-Policy-Data X(43)
            return new HouseRecord(line.substring(1, 11), line.substring(11, 21),
                    line.substring(21, 36),   // WF-H-PROPERTY-TYPE X(15)
                    line.substring(36, 39),   // WF-H-BEDROOMS 9(3)
                    line.substring(39, 47),   // WF-H-VALUE 9(8)
                    line.substring(47, 55),   // WF-H-POSTCODE X(8)
                    line.substring(55, 64));  // WF-H-HOUSE-NAME X(9)
        }

        String expectedPropertyType() {
            return propertyType.stripTrailing();
        }

        String expectedPostcode() {
            return postcode.stripTrailing();
        }
    }

    static Stream<HouseRecord> houseRecords() throws IOException {
        return PolicySeedDataTest.ksdspolyLines().stream().filter(l -> l.startsWith("H")).map(HouseRecord::parse);
    }

    @Test
    void sampleFileHasThreeHousePolicies() throws IOException {
        assertThat(houseRecords().map(r -> r.customerNumber() + "/" + r.policyNumber()))
                .containsExactly("0000000004/0000000006", "0000000006/0000000007", "0000000009/0000000008");
    }

    @ParameterizedTest(name = "house policy {index}")
    @MethodSource("houseRecords")
    void returnsEveryHouseFieldAsInSampleRecordAndDb2Cre(HouseRecord r) throws Exception {
        Db2CreInserts jcl = PolicySeedDataTest.db2cre();
        long policyNumber = Long.parseLong(r.policyNumber());
        Map<String, String> p = jcl.findByPolicyNumber("policy", policyNumber).orElseThrow();
        Map<String, String> h = jcl.findByPolicyNumber("house", policyNumber).orElseThrow();

        ResultActions result = mvc.perform(get("/policies/{c}/{p}", r.customerNumber(), r.policyNumber()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.customerNumber").value(r.customerNumber()))
                .andExpect(jsonPath("$.policyNumber").value(r.policyNumber()))
                .andExpect(jsonPath("$.policyType").value("H"))
                // fields of the ksdspoly.txt summary
                .andExpect(jsonPath("$.details.propertyType").value(r.expectedPropertyType()))
                .andExpect(jsonPath("$.details.bedrooms").value(expectedNumber(r.bedrooms(), h.get("bedrooms"))))
                .andExpect(jsonPath("$.details.value").value(expectedNumber(r.value(), h.get("value"))))
                .andExpect(jsonPath("$.details.postcode").value(r.expectedPostcode()))
                .andExpect(jsonPath("$.details.houseName").value(expectedHouseName(r.houseName(), h.get("housename"))))
                // columns only in db2cre.jcl
                .andExpect(jsonPath("$.details.houseNumber").value(h.get("housenumber").stripTrailing()))
                .andExpect(jsonPath("$.details.length()").value(6))
                .andExpect(jsonPath("$.common.issueDate").value(p.get("issuedate")))
                .andExpect(jsonPath("$.common.expiryDate").value(p.get("expirydate")))
                .andExpect(jsonPath("$.common.lastChanged").value(db2Timestamp(p.get("lastchanged"))))
                .andExpect(jsonPath("$.common.brokerId").value(Long.parseLong(p.get("brokerid"))))
                .andExpect(jsonPath("$.common.brokersReference").value(p.get("brokersreference").stripTrailing()))
                .andExpect(jsonPath("$.common.payment").value(Long.parseLong(p.get("payment"))))
                .andExpect(jsonPath("$.common.length()").value(6))
                .andExpect(jsonPath("$.length()").value(5));
        // The summary and the INSERT must agree wherever the summary is valid (FARM bedrooms is the exception).
        assertThat(r.expectedPropertyType()).isEqualTo(h.get("propertytype"));
        assertThat(r.expectedPostcode()).isEqualTo(h.get("postcode"));
        assertThat(Long.parseLong(r.value())).isEqualTo(Long.parseLong(h.get("value")));
    }

    /** A {@code PIC 9(n)} summary field; not all digits is invalid data, so the db2cre.jcl value applies. */
    private static long expectedNumber(String field, String db2creLiteral) {
        return field.matches("[0-9]+") ? Long.parseLong(field) : Long.parseLong(db2creLiteral.trim());
    }

    /** {@code WF-H-HOUSE-NAME X(9)} is a truncated summary of the 20-character Db2 name. */
    private static String expectedHouseName(String summary, String db2creName) {
        String s = summary.stripTrailing();
        String d = db2creName.stripTrailing();
        return !s.isEmpty() && d.length() > s.length() && d.startsWith(s) ? d : s;
    }

    /** SQL literal {@code yyyy-MM-dd HH:mm:ss} as a Db2 {@code PIC X(26)} timestamp. */
    private static String db2Timestamp(String literal) {
        String t = literal.replace(' ', '-').replace(':', '.');
        return t.contains(".") && t.length() > 19 ? (t + "000000").substring(0, 26) : t + ".000000";
    }

    @Test
    void knownSampleDataQuirksAreResolvedAsDocumented() throws Exception {
        // Policy 6: WF-H-BEDROOMS is "5 0" (not numeric) -> db2cre.jcl bedrooms 5
        mvc.perform(get("/policies/4/6")).andExpect(status().isOk())
                .andExpect(jsonPath("$.details.bedrooms").value(5))
                .andExpect(jsonPath("$.details.houseNumber").value("   5"));
        // Policy 7: file says type H with bedrooms "004"; db2cre.jcl says POLICYTYPE 'C' and bedrooms 8. File wins.
        mvc.perform(get("/policies/0000000006/0000000007")).andExpect(status().isOk())
                .andExpect(jsonPath("$.policyType").value("H"))
                .andExpect(jsonPath("$.details.bedrooms").value(4))
                .andExpect(jsonPath("$.details.houseName").value("HOME FARM"));
        // Policy 8: WF-H-BEDROOMS is "1 0" -> 1; house number keeps its leading space
        mvc.perform(get("/policies/9/0000000008")).andExpect(status().isOk())
                .andExpect(jsonPath("$.details.bedrooms").value(1))
                .andExpect(jsonPath("$.details.houseNumber").value(" 12b"))
                .andExpect(jsonPath("$.details.postcode").value("E15WW"));
    }

    @ParameterizedTest
    @CsvSource({
            "4, 7",                       // House policy 7 belongs to customer 6
            "0000000009, 0000000006",     // House policy 6 belongs to customer 4
            "6, 8",                       // House policy 8 belongs to customer 9
            "4, 11",                      // customer 4 exists, no policy 11
            "0000000006, 0000000099"})    // customer 6 exists, no policy 99
    void notFoundReturns404WithReturnCode01(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @ParameterizedTest
    @CsvSource({
            "abc, 6", "4, 6x", "-4, 6", "4, -6",
            "00000000004, 6", "4, 00000000006", "12345678901, 0000000006"})
    void malformedInputReturns400WithReturnCode98(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }

    /** Inserts a POLICY + HOUSE pair inside the test transaction (rolled back afterwards). */
    private void insertHouse(long policy, Integer brokerId, String brokersRef, Integer payment,
                             String propertyType, Integer bedrooms, Integer value, String houseNumber) {
        jdbc.update("""
                INSERT INTO POLICY (POLICYNUMBER, CUSTOMERNUMBER, ISSUEDATE, EXPIRYDATE, POLICYTYPE, LASTCHANGED,
                                    BROKERID, BROKERSREFERENCE, PAYMENT, COMMISSION)
                VALUES (?, 4, DATE '2020-01-01', DATE '2020-12-31', 'H', TIMESTAMP '2020-01-01 10:00:00.123456',
                        ?, ?, ?, NULL)""", policy, brokerId, brokersRef, payment);
        jdbc.update("""
                INSERT INTO HOUSE (POLICYNUMBER, PROPERTYTYPE, BEDROOMS, "VALUE", HOUSENAME, HOUSENUMBER, POSTCODE)
                VALUES (?, ?, ?, ?, 'X', ?, 'AB1 2CD')""", policy, propertyType, bedrooms, value, houseNumber);
    }

    @Test
    @Transactional
    void nullBrokerColumnsHaveIndicatorsAndDefaultToInitializedValues() throws Exception {
        insertHouse(900001, null, null, null, "HOUSE", 3, 250000, "  10");
        mvc.perform(get("/policies/4/900001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.common.brokerId").value(0))
                .andExpect(jsonPath("$.common.brokersReference").value(""))
                .andExpect(jsonPath("$.common.payment").value(0))
                .andExpect(jsonPath("$.common.lastChanged").value("2020-01-01-10.00.00.123456"));
    }

    @Test
    @Transactional
    void nullHouseColumnWithoutIndicatorIsSqlcode305AndReturns500WithReturnCode90() throws Exception {
        insertHouse(900002, 1, "REF", 100, "HOUSE", null, 250000, "  10");
        mvc.perform(get("/policies/4/900002"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.returnCode").value("90"));
    }

    @Test
    @Transactional
    void numericMovesDropSignAndTruncateHighOrderDigitsLikeCobol() throws Exception {
        insertHouse(900003, 1, "REF", 100, "HOUSE", 1234, -123456789, "  10");
        mvc.perform(get("/policies/4/900003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.bedrooms").value(234))
                .andExpect(jsonPath("$.details.value").value(23456789));
    }

    @Test
    void houseInquiryIdentifiesItsParagraph() {
        HousePolicyInquiry inquiry = new HousePolicyInquiry(jdbc);
        assertThat(List.of(inquiry.policyType(), inquiry.requestId())).containsExactly("H", "01IHOU");
    }
}
