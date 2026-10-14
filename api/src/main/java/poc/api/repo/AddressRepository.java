package poc.api.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.Address;
import poc.api.model.Dto.AddressInput;

@Repository
public class AddressRepository {
    private static final String COLS = "address_id, customer_id, addr_type, line1, city, postcode, country, is_primary";

    private final JdbcClient jdbc;
    private final Ids ids;

    AddressRepository(JdbcClient jdbc, Ids ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    private static Address map(ResultSet rs, int row) throws SQLException {
        Cols c = new Cols(rs);
        return new Address(c.integer(), c.integer(), c.string(), c.string(), c.string(), c.string(), c.string(), c.bool());
    }

    public List<Address> list(int customerId) {
        return jdbc.sql("SELECT " + COLS + " FROM address WHERE customer_id = ? ORDER BY address_id")
            .param(customerId).query(AddressRepository::map).list();
    }

    public Optional<Address> find(int customerId, int addressId) {
        return jdbc.sql("SELECT " + COLS + " FROM address WHERE customer_id = ? AND address_id = ?")
            .params(customerId, addressId).query(AddressRepository::map).optional();
    }

    public Address create(int customerId, AddressInput in) {
        int id = ids.next("address");
        boolean primary = Boolean.TRUE.equals(in.primary());
        if (primary) clearPrimary(customerId, id);
        jdbc.sql("INSERT INTO address (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
            .params(id, customerId, in.addrType(), in.line1(), in.city(), in.postcode(), in.country(), primary).update();
        return find(customerId, id).orElseThrow();
    }

    public boolean update(int customerId, int addressId, AddressInput in) {
        boolean primary = Boolean.TRUE.equals(in.primary());
        if (primary) clearPrimary(customerId, addressId);
        return jdbc.sql("UPDATE address SET addr_type = ?, line1 = ?, city = ?, postcode = ?, country = ?, is_primary = ? "
                + "WHERE customer_id = ? AND address_id = ?")
            .params(in.addrType(), in.line1(), in.city(), in.postcode(), in.country(), primary, customerId, addressId)
            .update() > 0;
    }

    public boolean delete(int customerId, int addressId) {
        return jdbc.sql("DELETE FROM address WHERE customer_id = ? AND address_id = ?")
            .params(customerId, addressId).update() > 0;
    }

    private void clearPrimary(int customerId, int exceptId) {
        jdbc.sql("UPDATE address SET is_primary = FALSE WHERE customer_id = ? AND address_id <> ?")
            .params(customerId, exceptId).update();
    }
}
