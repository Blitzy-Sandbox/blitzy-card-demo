/**
 * formatExpiry.ts — safe display formatter for the Card Detail expiry field.
 *
 * The Card Detail tracer screen must surface the card expiry as **MM/YYYY**
 * (architecture.md §"The rendered Card Detail screen surfaces these fields":
 * "Expiry (mm/yyyy)"), but the value that flows through the tracer
 * (UI ← BFF ← card-svc ← Oracle) is the legacy `CARD-EXPIRAION-DATE` stored as an
 * ISO-like `YYYY-MM-DD` string. This pure helper converts that value for display
 * without ever throwing and without hiding data: any value it does not recognize
 * is returned unchanged so an unexpected backend format degrades gracefully to the
 * raw string rather than to a blank or a crash.
 *
 * Accepted inputs and their results:
 *   - `"2027-12-31"` / `"2027-12"`  → `"12/2027"`   (ISO date or year-month)
 *   - `"2027-9"`                    → `"09/2027"`   (single-digit month is padded)
 *   - `"12/2027"`                   → `"12/2027"`   (already MM/YYYY; normalized)
 *   - `""` / `null` / `undefined`   → `""`          (nothing to show)
 *   - anything else                 → the trimmed input unchanged (safe fallback)
 *
 * A month outside 1–12 is treated as an unrecognized format and returned unchanged,
 * so a malformed upstream value is shown verbatim rather than reformatted into a
 * misleading date.
 *
 * @param raw the raw expiry value from the Card DTO (`expiryDate`); may be null/undefined
 * @returns the expiry formatted as `MM/YYYY`, or a safe fallback (see above)
 */
export function formatExpiry(raw: string | null | undefined): string {
  if (raw == null) {
    return '';
  }
  const value = raw.trim();
  if (value === '') {
    return '';
  }

  // ISO-like `YYYY-MM` or `YYYY-MM-DD` (the shape the tracer actually delivers).
  const isoMatch = /^(\d{4})-(\d{1,2})(?:-\d{1,2})?$/.exec(value);
  if (isoMatch) {
    const year = isoMatch[1];
    const month = Number(isoMatch[2]);
    if (month >= 1 && month <= 12) {
      return `${String(month).padStart(2, '0')}/${year}`;
    }
    return value;
  }

  // Already `M/YYYY` or `MM/YYYY` — normalize the month to two digits.
  const mmYyyyMatch = /^(\d{1,2})\/(\d{4})$/.exec(value);
  if (mmYyyyMatch) {
    const month = Number(mmYyyyMatch[1]);
    const year = mmYyyyMatch[2];
    if (month >= 1 && month <= 12) {
      return `${String(month).padStart(2, '0')}/${year}`;
    }
  }

  // Unrecognized format: return the (trimmed) original unchanged — never throw,
  // never blank out real data.
  return value;
}
