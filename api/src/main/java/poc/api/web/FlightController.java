package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import poc.api.model.Dto.Page;
import poc.api.model.TravelDto.CancelFlightRequest;
import poc.api.model.TravelDto.DelayRequest;
import poc.api.model.TravelDto.Flight;
import poc.api.model.TravelDto.FlightEvent;
import poc.api.model.TravelDto.FlightInput;
import poc.api.model.TravelDto.FlightOpResult;
import poc.api.model.TravelDto.FlightStatus;
import poc.api.repo.TravelRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.BadRequest;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/flights")
@Validated
@Tag(name = "Flights")
public class FlightController {
    private final TravelRepository travel;
    private final ComputeService compute;

    FlightController(TravelRepository travel, ComputeService compute) {
        this.travel = travel;
        this.compute = compute;
    }

    @GetMapping
    @Operation(summary = "List flights", description = "Filter by route, status and departure window; soonest first.")
    Page<Flight> list(@RequestParam(required = false) @Parameter(description = "Origin IATA code", example = "DXB") String origin,
                      @RequestParam(required = false) @Parameter(description = "Destination IATA code", example = "LHR") String destination,
                      @RequestParam(required = false) FlightStatus status,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "Departing at or after (ISO-8601)") Instant from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "Departing before (ISO-8601)") Instant to,
                      @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                      @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return travel.flights(blank(origin), blank(destination), status == null ? null : status.name(), from, to, limit, offset);
    }

    @GetMapping("/{flightId}")
    @Operation(summary = "Get a flight with its seat inventory")
    Flight get(@PathVariable int flightId) {
        return travel.flight(flightId).orElseThrow(() -> new NotFound("Flight", flightId));
    }

    @PostMapping
    @Operation(summary = "Create a flight", description = "Starts SCHEDULED with no delay. Provide the seat inventory per cabin (seats and base fare).")
    ResponseEntity<Flight> create(@Valid @RequestBody FlightInput in) {
        validate(in, true);
        Flight f = travel.createFlight(in);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(f.flightId()).toUri()).body(f);
    }

    @PutMapping("/{flightId}")
    @Operation(summary = "Change a flight's schedule details",
        description = "Only flights that have not operated (SCHEDULED or DELAYED) can be edited. Status and delay change through the delay and cancel operations.")
    Flight update(@PathVariable int flightId, @Valid @RequestBody FlightInput in) {
        Flight f = get(flightId);
        if (!"SCHEDULED".equals(f.status()) && !"DELAYED".equals(f.status()))
            throw new Conflict("Flight " + flightId + " is " + f.status() + " and can no longer be edited");
        validate(in, false);
        travel.updateFlight(flightId, in);
        return get(flightId);
    }

    @DeleteMapping("/{flightId}")
    @Operation(summary = "Delete a flight", description = "Refused with 409 if any booking segment references it (cancel the flight instead).")
    ResponseEntity<Void> delete(@PathVariable int flightId) {
        get(flightId);
        if (travel.segmentCount(flightId) > 0) throw new Conflict("Flight " + flightId + " has bookings; cancel it instead of deleting");
        travel.deleteFlight(flightId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{flightId}/events")
    @Operation(summary = "Operational events for a flight", description = "Delays and cancellations, oldest first.")
    List<FlightEvent> events(@PathVariable int flightId) {
        get(flightId);
        return travel.events(flightId);
    }

    @PostMapping("/{flightId}/delay")
    @Operation(summary = "Record a delay",
        description = "Runs the record-delay job (replaces sp_record_delay): sets the flight DELAYED with the delay in minutes, writes a flight event "
            + "and returns how many confirmed bookings are affected. 409 if the flight has already operated or is cancelled.")
    FlightOpResult delay(@PathVariable int flightId, @Valid @RequestBody DelayRequest req) {
        return compute.delayFlight(flightId, req);
    }

    @PostMapping("/{flightId}/cancel")
    @Operation(summary = "Cancel a flight",
        description = "Runs the cancel-flight job (replaces sp_cancel_flight). Passengers' compensation is then produced by POST /disruptions/process.")
    FlightOpResult cancel(@PathVariable int flightId, @RequestBody(required = false) @Valid CancelFlightRequest req) {
        return compute.cancelFlight(flightId, req);
    }

    @GetMapping("/{flightId}/impact")
    @Operation(summary = "Hub: who is affected by a disruption, ranked by customer value",
        description = "Joins the flight's passengers (Oracle) with their customers (Sybase): loyalty tier, points and completed-order spend. "
            + "Passengers are ranked by priority (tier, cabin, retail spend) and each gets the base compensation for the flight's delay or "
            + "cancellation and a tier-weighted suggested amount (Sybase's loyalty multiplier). Runs one job per cluster node on the data it owns.")
    Map<String, Object> impact(@PathVariable int flightId) {
        get(flightId);
        return compute.disruptionImpact(flightId);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void validate(FlightInput in, boolean creating) {
        if (!in.arrTime().isAfter(in.depTime())) throw new BadRequest("arrTime must be after depTime");
        if (in.origin().equalsIgnoreCase(in.destination())) throw new BadRequest("origin and destination must differ");
        for (String code : new String[] {in.origin(), in.destination()})
            if (travel.airport(code).isEmpty()) throw new BadRequest("Unknown airport " + code.toUpperCase());
        if (creating && (in.inventory() == null || in.inventory().isEmpty())) throw new BadRequest("inventory is required when creating a flight");
        if (creating && in.inventory().stream().map(i -> i.cabin()).distinct().count() != in.inventory().size())
            throw new BadRequest("inventory lists a cabin more than once");
    }
}
