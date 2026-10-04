package org.genapp.customer;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** LGICDB01 {@code EVALUATE SQLCODE} branches that the H2 sample data cannot trigger. */
@WebMvcTest(CustomerController.class)
@Import(CustomerInquiryService.class)
class CustomerInquiryErrorMappingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomerRepository repository;

    @Test
    void db2ErrorReturns500WithReturnCode90() throws Exception {
        when(repository.findByCustomerNumber(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("down", new SQLException("down", "57011", -904)));
        mvc.perform(get("/customers/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.returnCode").value("90"));
    }

    @Test
    void sqlcodeMinus913IsReportedAsNotFoundLikeLgicdb01() throws Exception {
        when(repository.findByCustomerNumber(anyLong()))
                .thenThrow(new PessimisticLockingFailureException("timeout",
                        new SQLException("timeout", "57033", -913)));
        mvc.perform(get("/customers/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.returnCode").value("01"));
    }

    @Test
    void sqlcodeMinus911IsStillADb2Error() throws Exception {
        when(repository.findByCustomerNumber(anyLong()))
                .thenThrow(new PessimisticLockingFailureException("deadlock",
                        new SQLException("deadlock", "40001", -911)));
        mvc.perform(get("/customers/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.returnCode").value("90"));
    }
}
