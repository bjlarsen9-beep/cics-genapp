package org.genapp.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Type-independent not-found and malformed-input cases for GET /policies/{customerNumber}/{policyNumber}. */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyInquiryValidationTest {

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @CsvSource({
            "1, 1",            // customer 1 exists in ksdspoly.txt, but policy 1 belongs to customer 2
            "2, 10",           // policy 10 belongs to customer 1
            "1, 11",           // no policy 11
            "11, 1",           // no customer 11
            "0, 0",
            "9999999999, 9999999999"})
    void unknownCustomerPolicyPairReturns404WithReturnCode01(String customer, String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, policy))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12a", "-1", "1.0", "+1", " 1", "12345678901", "00000000001"})
    void malformedCustomerNumberReturns400WithReturnCode98(String customer) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", customer, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12a", "-1", "1.0", "+1", " 1", "12345678901", "00000000001"})
    void malformedPolicyNumberReturns400WithReturnCode98(String policy) throws Exception {
        mvc.perform(get("/policies/{c}/{p}", "2", policy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("98"));
    }
}
