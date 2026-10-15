package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import poc.api.model.TravelDto.Airport;
import poc.api.repo.TravelRepository;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/airports")
@Tag(name = "Airports")
public class AirportController {
    private final TravelRepository travel;

    AirportController(TravelRepository travel) {
        this.travel = travel;
    }

    @GetMapping
    @Operation(summary = "List airports", description = "Reference data from the Oracle travel system, replicated to every GridGain node.")
    List<Airport> list() {
        return travel.airports();
    }

    @GetMapping("/{iata}")
    @Operation(summary = "Get an airport by IATA code")
    Airport get(@PathVariable String iata) {
        return travel.airport(iata).orElseThrow(() -> new NotFound("Airport", iata));
    }
}
