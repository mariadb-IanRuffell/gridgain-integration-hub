package poc.api.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal configuration for the web-slice tests: no component scan, so GridGainConfig (which opens a real cluster connection)
 * and the repositories are never created. Each test imports the controller under test and mocks its collaborators.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class WebTestApp {
}
