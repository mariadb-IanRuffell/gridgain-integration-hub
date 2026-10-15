package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import poc.api.model.Dto.Page;
import poc.api.model.TravelDto.BookFlightRequest;
import poc.api.model.TravelDto.BookFlightResult;
import poc.api.model.TravelDto.Booking;
import poc.api.model.TravelDto.BookingStatus;
import poc.api.model.TravelDto.CancelBookingResult;
import poc.api.repo.TravelRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/bookings")
@Validated
@Tag(name = "Bookings")
public class BookingController {
    private final TravelRepository travel;
    private final ComputeService compute;

    BookingController(TravelRepository travel, ComputeService compute) {
        this.travel = travel;
        this.compute = compute;
    }

    @GetMapping
    @Operation(summary = "List bookings", description = "Newest first. customerId is the Sybase customer id. Segments are only included by GET /bookings/{id}.")
    Page<Booking> list(@RequestParam(required = false) Integer customerId,
                       @RequestParam(required = false) BookingStatus status,
                       @RequestParam(required = false) Integer flightId,
                       @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                       @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return travel.bookings(customerId, status == null ? null : status.name(), flightId, limit, offset);
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Get a booking with its segments")
    Booking get(@PathVariable int bookingId) {
        return travel.booking(bookingId).orElseThrow(() -> new NotFound("Booking", bookingId));
    }

    @PostMapping
    @Operation(summary = "Book a flight",
        description = "Runs the book-flight job (replaces sp_book_flight): one transaction on the customer's node that takes a seat from the flight's "
            + "cabin inventory, assigns the next seat number and creates the booking and its segment, with miles earned. "
            + "404 flight not found, 409 flight not open or cabin sold out, 400 bad cabin or passenger.")
    ResponseEntity<BookFlightResult> book(@Valid @RequestBody BookFlightRequest req) {
        BookFlightResult r = compute.bookFlight(req);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(r.bookingId()).toUri()).body(r);
    }

    @PostMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking",
        description = "Runs the cancel-booking job (replaces sp_cancel_booking): cancels every future segment, releases the seats and returns the refund "
            + "(more than 72 h before departure 90%, more than 24 h 50%, otherwise nothing). 404 unknown, 409 already cancelled or nothing to cancel.")
    CancelBookingResult cancel(@PathVariable int bookingId) {
        return compute.cancelBooking(bookingId);
    }
}
