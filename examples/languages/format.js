'use strict';

/*
 * Formatting helpers shared by the storefront pages.
 * They only depend on the built-in Intl API.
 */

/**
 * Formats an amount of cents as a localized price.
 *
 * @param {number} cents the amount in cents
 * @param {string} currency the ISO currency code
 * @returns {string} the formatted price
 */
function formatPrice(cents, currency) {
  // Intl.NumberFormat is slow to construct,
  // so a single formatter per currency is cached
  // and reused for every later call.
  const formatter = cachedFormatter(currency);
  return formatter.format(cents / 100);
}

const cache = new Map();

function cachedFormatter(currency) {
  if (!cache.has(currency)) {
    cache.set(currency, new Intl.NumberFormat('en-US', { style: 'currency', currency }));
  }
  return cache.get(currency);
}

const docsUrl = 'https://example.com/format// not a comment';

module.exports = { formatPrice, docsUrl };
