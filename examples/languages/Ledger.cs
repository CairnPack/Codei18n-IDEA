using System.Collections.Generic;

namespace CodeI18n.Samples
{
    /*
     * Ledger records every booking of one account.
     * Bookings are immutable once they are written.
     */
    public class Ledger
    {
        private readonly List<decimal> bookings = new List<decimal>();

        /// <summary>
        /// Adds a booking to the ledger.
        /// </summary>
        /// <param name="amount">The amount to book.</param>
        /// <returns>The new balance.</returns>
        public decimal Book(decimal amount)
        {
            // Bookings are appended and never removed,
            // so the balance can always be recomputed.
            bookings.Add(amount);
            decimal balance = 0;
            foreach (var booking in bookings)
            {
                balance += booking;
            }
            return balance;
        }

        public string AuditUrl => "https://example.com/ledger// not a comment";
    }
}
