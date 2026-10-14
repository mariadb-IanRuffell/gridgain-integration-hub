package poc.api.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Counts business API calls. The dashboard's own polling (/api/v1/dashboard) is excluded so it does not pollute the graphs. */
@Component
public class RequestMetricsFilter extends OncePerRequestFilter {
    private final RequestMetrics metrics;

    RequestMetricsFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/v1/") || p.startsWith("/api/v1/dashboard");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            metrics.record(req.getMethod(), res.getStatus(), System.nanoTime() - start);
        }
    }
}
