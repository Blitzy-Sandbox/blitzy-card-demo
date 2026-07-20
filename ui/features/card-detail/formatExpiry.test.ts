import { describe, it, expect } from 'vitest';

import { formatExpiry } from './formatExpiry';

/**
 * Unit tests for {@link formatExpiry} (finding P4-m02).
 *
 * Pins the required MM/YYYY display format and every safe-fallback branch so the
 * Card Detail tracer never regresses to the raw `YYYY-MM-DD` value and never
 * throws on unexpected input.
 */
describe('formatExpiry', () => {
  it('formats an ISO YYYY-MM-DD date as MM/YYYY', () => {
    expect(formatExpiry('2027-12-31')).toBe('12/2027');
  });

  it('formats an ISO YYYY-MM value as MM/YYYY', () => {
    expect(formatExpiry('2027-12')).toBe('12/2027');
  });

  it('pads a single-digit month to two digits', () => {
    expect(formatExpiry('2027-9')).toBe('09/2027');
    expect(formatExpiry('2027-9-5')).toBe('09/2027');
  });

  it('normalizes an already MM/YYYY value (padding the month)', () => {
    expect(formatExpiry('9/2027')).toBe('09/2027');
    expect(formatExpiry('12/2027')).toBe('12/2027');
  });

  it('returns an empty string for null, undefined, or blank input', () => {
    expect(formatExpiry(null)).toBe('');
    expect(formatExpiry(undefined)).toBe('');
    expect(formatExpiry('   ')).toBe('');
  });

  it('returns the trimmed original for an unrecognized format (never throws)', () => {
    expect(formatExpiry('not-a-date')).toBe('not-a-date');
    expect(formatExpiry('  2027/12/31  ')).toBe('2027/12/31');
  });

  it('treats an out-of-range month as unrecognized and returns it unchanged', () => {
    expect(formatExpiry('2027-13')).toBe('2027-13');
    expect(formatExpiry('2027-00')).toBe('2027-00');
    expect(formatExpiry('13/2027')).toBe('13/2027');
  });
});
