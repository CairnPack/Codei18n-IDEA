package dev.codei18n.samples;

import java.util.ArrayList;
import java.util.List;

/*
 * OrderService collects the orders of one checkout session.
 * Orders are kept in insertion order until the session is closed.
 */
public final class OrderService {
    private final List<String> orders = new ArrayList<>();

    /**
     * Places a new order for the given customer.
     *
     * @param customer the customer who places the order
     * @return the number of orders in this session
     */
    public int place(String customer) {
        // Normalize the customer name before storing it,
        // so that duplicate orders can be detected later.
        orders.add(customer.trim());
        return orders.size();
    }

    public String endpoint() {
        return "https://example.com/orders// not a comment";
    }
}
