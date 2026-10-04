package org.genapp.customer;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Parity tests: every sample record in base/data/ksdscust.txt, read here with the COMMAREA offsets from
 * lgcmarea.cpy (independently of the service's own seed loader), must come back field for field.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerInquiryParityTest {

    private static final Path KSDSCUST = Path.of(System.getProperty("genapp.base.dir", "../../base"),
            "data", "ksdscust.txt");

    @Autowired
    private MockMvc mvc;

    /** COMMAREA field name, JSON property, offset in the 225-byte record, PIC X length. */
    private record Field(String cobolName, String json, int offset, int length) {
    }

    private static final List<Field> FIELDS = List.of(
            new Field("CA-CUSTOMER-NUM", "customerNumber", 0, 10),
            new Field("CA-FIRST-NAME", "firstName", 10, 10),
            new Field("CA-LAST-NAME", "lastName", 20, 20),
            new Field("CA-DOB", "dateOfBirth", 40, 10),
            new Field("CA-HOUSE-NAME", "houseName", 50, 20),
            new Field("CA-HOUSE-NUM", "houseNumber", 70, 4),
            new Field("CA-POSTCODE", "postcode", 74, 8),
            new Field("CA-PHONE-MOBILE", "phoneMobile", 85, 20),
            new Field("CA-PHONE-HOME", "phoneHome", 105, 20),
            new Field("CA-EMAIL-ADDRESS", "emailAddress", 125, 100));

    static Stream<String> sampleRecords() throws IOException {
        return Files.readAllLines(KSDSCUST, StandardCharsets.ISO_8859_1).stream().filter(l -> !l.isBlank());
    }

    @Test
    void sampleFileHasTenFixedWidthRecords() throws IOException {
        List<String> records = sampleRecords().toList();
        assertThat(records).hasSize(10);
        assertThat(records).allSatisfy(r -> assertThat(r).hasSize(225));
    }

    @ParameterizedTest(name = "customer {index}")
    @MethodSource("sampleRecords")
    void returnsEveryCustomerFieldExactlyAsInSampleRecord(String record) throws Exception {
        String customerNumber = record.substring(0, 10);
        var result = mvc.perform(get("/customers/{n}", customerNumber))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.numberOfPolicies").value(0))
                .andExpect(jsonPath("$.length()").value(FIELDS.size() + 1));
        for (Field f : FIELDS) {
            String expected = record.substring(f.offset(), f.offset() + f.length()).stripTrailing();
            result.andExpect(jsonPath("$." + f.json()).value(expected));
        }
    }

    @Test
    void customerOneMatchesHandCheckedValues() throws Exception {
        Map<String, Object> expected = Map.ofEntries(
                Map.entry("customerNumber", "0000000001"),
                Map.entry("firstName", "ANDREW"),
                Map.entry("lastName", "PANDY"),
                Map.entry("dateOfBirth", "1950-07-11"),
                Map.entry("houseName", ""),
                Map.entry("houseNumber", "34"),
                Map.entry("postcode", "PI101O"),
                Map.entry("numberOfPolicies", 0),
                Map.entry("phoneMobile", "01962 811234"),
                Map.entry("phoneHome", "07799 123456"),
                Map.entry("emailAddress", "A.PANDY@BEEBHOUSE.COM"));
        var result = mvc.perform(get("/customers/1")).andExpect(status().isOk());
        for (var e : expected.entrySet()) {
            result.andExpect(jsonPath("$." + e.getKey()).value(e.getValue()));
        }
    }

    @Test
    void acceptsUnpaddedAndZeroPaddedCustomerNumbers() throws Exception {
        mvc.perform(get("/customers/10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerNumber").value("0000000010"));
        mvc.perform(get("/customers/0000000010")).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerNumber").value("0000000010"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"11", "0", "0000000099", "1000001", "9999999999"})
    void unknownCustomerReturns404WithReturnCode01(String customerNumber) throws Exception {
        mvc.perform(get("/customers/{n}", customerNumber))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12a", "-1", "1.0", "+1", " 1", "12345678901", "00000000001"})
    void malformedCustomerNumberReturns400WithReturnCode98(String customerNumber) throws Exception {
        mvc.perform(get("/customers/{n}", customerNumber))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }
}
