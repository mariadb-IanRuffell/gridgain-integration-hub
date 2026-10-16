package poc.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import({ApiExceptionHandlerTest.Boom.class, ApiExceptionHandler.class})
class ApiExceptionHandlerTest {
    @RestController
    @RequestMapping("/boom")
    static class Boom {
        @GetMapping("/db") String db() { throw new DataAccessResourceFailureException("wrapper", new RuntimeException("Failed to connect to server")); }
        @GetMapping("/unavailable") String unavailable() { throw new ApiExceptions.Unavailable("GridGain compute call failed", new RuntimeException()); }
        @GetMapping("/bad") String bad() { throw new ApiExceptions.BadRequest("cabin must match X"); }
        @GetMapping("/conflict") String conflict() { throw new ApiExceptions.Conflict("already there"); }
        @GetMapping("/missing") String missing() { throw new ApiExceptions.NotFound("Thing", 5); }
        @GetMapping("/int/{n}") String number(@org.springframework.web.bind.annotation.PathVariable int n) { return "ok"; }
    }

    @Autowired MockMvc mvc;

    @Test
    void everyErrorIsAnRfc7807Problem() throws Exception {
        mvc.perform(get("/boom/missing")).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.detail").value("Thing 5 not found"));
        mvc.perform(get("/boom/conflict")).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("already there"));
        mvc.perform(get("/boom/bad")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("cabin must match X"));
        mvc.perform(get("/boom/unavailable")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void databaseErrorsAre503WithTheRootCause() throws Exception {
        mvc.perform(get("/boom/db")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("GridGain SQL error: Failed to connect to server"));
    }

    @Test
    void aNonNumericPathVariableIs400() throws Exception {
        mvc.perform(get("/boom/int/abc")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid value 'abc' for parameter 'n'"));
    }
}
