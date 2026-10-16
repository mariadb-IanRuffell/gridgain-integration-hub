package poc.api.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestMetricsTest {
    @Test
    void classifiesReadsWritesAndErrors() {
        RequestMetrics m = new RequestMetrics();
        m.record("GET", 200, 2_000_000);
        m.record("HEAD", 200, 1_000_000);
        m.record("POST", 201, 3_000_000);
        m.record("DELETE", 204, 1_000_000);
        m.record("GET", 404, 1_000_000);
        m.record("PUT", 409, 1_000_000);
        m.record("GET", 503, 1_000_000);
        RequestMetrics.Snapshot s = m.snapshot();
        assertEquals(7, s.requests());
        assertEquals(4, s.reads());           // GET, HEAD, GET, GET
        assertEquals(3, s.writes());          // POST, DELETE, PUT
        assertEquals(2, s.clientErrors());
        assertEquals(1, s.serverErrors());
        assertEquals(10_000_000, s.nanos());
        assertTrue(s.uptimeSeconds() >= 0);
    }

    @Test
    void emptySnapshotIsZero() {
        RequestMetrics.Snapshot s = new RequestMetrics().snapshot();
        assertEquals(0, s.requests());
        assertEquals(0, s.nanos());
    }

    @Test
    void isSafeForConcurrentUse() throws Exception {
        RequestMetrics m = new RequestMetrics();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> { for (int k = 0; k < 5_000; k++) m.record("GET", 200, 1); });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(40_000, m.snapshot().requests());
        assertEquals(40_000, m.snapshot().nanos());
    }

    private RequestMetrics metrics = mock(RequestMetrics.class);
    private final RequestMetricsFilter filter = new RequestMetricsFilter(metrics);

    private void call(String method, String uri, int status, FilterChain chain) throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(status);
        filter.doFilter(req, res, chain);
    }

    @Test
    void filterRecordsBusinessCalls() throws Exception {
        call("GET", "/api/v1/customers/7", 200, mock(FilterChain.class));
        verify(metrics).record(eq("GET"), eq(200), anyLong());
    }

    @Test
    void filterSkipsTheDashboardAndNonApiPaths() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        call("GET", "/api/v1/dashboard/summary", 200, chain);
        call("GET", "/dashboard.html", 200, chain);
        call("GET", "/v3/api-docs", 200, chain);
        call("GET", "/", 200, chain);
        verifyNoInteractions(metrics);
        verify(chain, times(4)).doFilter(any(), any());   // still passed down the chain
    }

    @Test
    void filterRecordsEvenWhenTheHandlerThrows() throws Exception {
        FilterChain boom = (rq, rs) -> { throw new ServletException("boom"); };
        assertThrows(ServletException.class, () -> call("POST", "/api/v1/orders", 500, boom));
        verify(metrics).record(eq("POST"), eq(500), anyLong());
    }
}
