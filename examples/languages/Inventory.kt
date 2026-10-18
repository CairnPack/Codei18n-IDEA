package dev.codei18n.samples

/*
 * Inventory keeps the stock level of every product in memory.
 * It is not thread safe; wrap it when sharing between coroutines.
 */
class Inventory(private val warehouse: String) {
    private val stock = mutableMapOf<String, Int>()

    /**
     * Adds [quantity] units of the product identified by [sku].
     *
     * @param sku the stock keeping unit of the product
     * @return the new stock level
     */
    fun add(sku: String, quantity: Int): Int {
        // Reject negative quantities early so the stock
        // never drops below zero because of a bad request
        // coming from the import job.
        require(quantity >= 0) { "quantity must not be negative" }
        val level = stock.getOrDefault(sku, 0) + quantity
        stock[sku] = level
        return level
    }

    fun reportUrl(): String = "https://example.com/inventory// not a comment"
}
