package poc.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI customer360OpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Customer 360 API")
                .version("1.0.0")
                .description("""
                    CRUD and business operations over the Customer 360 data held in a GridGain 8.9 cluster
                    (replicated from Sybase ASE by CDC during the phased migration).

                    * Resource endpoints read and write GridGain tables through SQL.
                    * Business operations (`360`, `recalculate`, `scoring`, `POST /orders`) call the GridGain compute
                      jobs that replace the former Sybase stored procedures, so rules such as stock checks,
                      loyalty points and tier changes are applied atomically on the node that owns the customer.
                    * Timestamps are ISO-8601 UTC. Errors use RFC 7807 problem details.

                    Note: while Sybase is still the system of record, rows written through this API exist only in
                    GridGain (CDC is one-way, Sybase to GridGain).
                    """))
            .servers(List.of(new Server().url("http://localhost:8080").description("Local Docker")))
            .tags(List.of(
                new Tag().name("Customers").description("Customer master data"),
                new Tag().name("Addresses").description("Customer addresses (nested under a customer)"),
                new Tag().name("Products").description("Product catalogue and stock"),
                new Tag().name("Orders").description("Orders, line items and payments. Creating an order runs the place-order job"),
                new Tag().name("Interactions").description("Customer service interactions (nested under a customer)"),
                new Tag().name("Loyalty").description("Loyalty tiers, points ledger and churn score (read-only data)"),
                new Tag().name("Operations").description("Business operations implemented as GridGain compute jobs"),
                new Tag().name("Dashboard").description("Data behind the live dashboard page at /dashboard.html")));
    }
}
