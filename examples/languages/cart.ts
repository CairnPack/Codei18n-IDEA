export interface CartItem {
  sku: string;
  price: number;
  quantity: number;
}

/*
 * Shopping cart that lives in the browser session.
 * Prices are stored in cents to avoid rounding errors.
 */
export class Cart {
  private items: CartItem[] = [];

  /**
   * Adds an item to the cart.
   *
   * @param item the item to add
   * @returns the number of distinct items
   */
  add(item: CartItem): number {
    // Merge with an existing line when the same product
    // is added twice, instead of creating a second line.
    const existing = this.items.find((line) => line.sku === item.sku);
    if (existing) {
      existing.quantity += item.quantity;
    } else {
      this.items.push(item);
    }
    return this.items.length;
  }

  checkoutUrl(): string {
    return "https://example.com/cart// not a comment";
  }
}
