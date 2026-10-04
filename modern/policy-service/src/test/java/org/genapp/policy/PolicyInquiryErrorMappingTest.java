package org.genapp.policy;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** LGIPDB01 MAINLINE/paragraph branches that the H2 sample data cannot trigger. */
@WebMvcTest(PolicyController.class)
@Import(PolicyInquiryService.class)
class PolicyInquiryErrorMappingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PolicyRepository repository;

    @Test
    void db2ErrorReturns500WithReturnCode90() throws Exception {
        when(repository.findPolicyType(anyLong(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("down", new SQLException("down", "57011", -904)));
        mvc.perform(get("/policies/1/10"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.returnCode").value("90"));
    }

    @Test
    void policyTypeWithoutAParagraphReturns400WithReturnCode99() throws Exception {
        when(repository.findPolicyType(anyLong(), anyLong())).thenReturn(Optional.of("X"));
        mvc.perform(get("/policies/1/10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.returnCode").value("99"));
    }
}
