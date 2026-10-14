package poc.api.config;

import org.apache.ignite.Ignition;
import org.apache.ignite.client.ClientRetryAllPolicy;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GridGainConfig {
    /**
     * Thin client over all cluster nodes (fails over if one is down). Affinity awareness is off because the
     * nodes advertise container-network addresses that callers outside the Docker network cannot reach.
     */
    @Bean(destroyMethod = "close")
    IgniteClient igniteClient(@Value("${gridgain.addresses}") String addresses) {
        return Ignition.startClient(new ClientConfiguration()
            .setAddresses(addresses.split(","))
            .setAffinityAwarenessEnabled(false)
            .setRetryPolicy(new ClientRetryAllPolicy()));
    }
}
