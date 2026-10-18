/*
 * Invoice model used by the billing screen.
 * Totals are recomputed whenever a line changes.
 */
class Invoice {
  final List<double> lines = [];

  /// Returns the total amount of all invoice lines.
  ///
  /// The result already includes the [taxRate].
  double total(double taxRate) {
    // Sum the lines first and apply the tax once,
    // which avoids accumulating rounding errors.
    final net = lines.fold<double>(0, (sum, line) => sum + line);
    return net * (1 + taxRate);
  }

  String get portalUrl => 'https://example.com/invoice// not a comment';
}
