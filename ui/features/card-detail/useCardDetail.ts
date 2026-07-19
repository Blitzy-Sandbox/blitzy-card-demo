/**
 * useCardDetail — Card Detail tracer data hook.
 *
 * This is the data hook for THE ONE LIVE VERTICAL TRACER SLICE of the CardDemo
 * walking skeleton. It performs the single real network call of the entire
 * tracer and drives the Card Detail screen's real loading / data / empty / error
 * states. It is a pure TypeScript data hook — it renders NO JSX.
 *
 * Tracer flow (the one fully-wired seam; everything else is a typed stub):
 *   UI (this hook) → BFF (GET /api/cards/{cardNumber}) → card-svc → Oracle FREEPDB1 (seeded row)
 *
 * The UI binds ONLY to the BFF: the sole permitted network call in this file is
 * the generated `CardDetailApi.getCardDetail(...)`. The `X-Correlation-ID` and
 * `Authorization: Bearer <token>` headers are attached automatically by the
 * shared Axios request interceptor (see `../../app/api/correlationId`); they are
 * never set here, and `xCorrelationID` is never passed to the client.
 *
 * Provenance (legacy reference, read-only):
 *   [SRC: COCRDSLC | CARDDAT]   — legacy Credit Card View program reading the
 *                                 CARDDAT VSAM dataset keyed on the 16-char card
 *                                 number; the modern equivalent is this hook's
 *                                 natural-key read via getCardDetail({ cardNumber }).
 *   [SRC: CVACT02Y.cpy:L4-L10]  — CARD-RECORD copybook whose fields map to the
 *                                 CardDetail DTO (CARD-CVV-CD is intentionally
 *                                 excluded from the read DTO).
 */

import { useEffect, useState } from 'react';
import { isAxiosError } from 'axios';

import { CardDetailApi, type CardDetail } from '../../app/api/generated';
import { apiConfig, axiosInstance } from '../../app/api/correlationId';

/**
 * Strongly-typed result returned by {@link useCardDetail} and consumed by the
 * `CardDetail.tsx` screen (and by tests).
 *
 * The screen selects exactly one visual state from this snapshot, following the
 * precedence loading → error → notFound → data:
 * - `loading` true  → loading state (Skeleton / CircularProgress)
 * - `error` set     → error state (Alert) — any non-404 rejection (500 / network)
 * - `notFound` true → empty state (Typography) — HTTP 404, or no card number supplied
 * - `data` set      → populated Card Detail
 */
export interface UseCardDetailResult {
  /** The fetched card, or `null` until a successful 200 response arrives. */
  data: CardDetail | null;
  /** True while the single BFF request is in flight. */
  loading: boolean;
  /**
   * The rejection value for any non-404 failure (500 / network / unknown);
   * otherwise `null`. Deliberately typed `unknown` to honour the strict
   * `useUnknownInCatchVariables` setting — never widen to `any`.
   */
  error: unknown;
  /** True when the card was not found (HTTP 404) or no card number was supplied. */
  notFound: boolean;
}

/**
 * Fetch a single card's detail by its natural 16-digit card number through the
 * BFF — the LIVE tracer read. The hook owns the loading / data / empty / error
 * lifecycle and is safe under React 19 StrictMode effect double-invocation.
 *
 * @param cardNumber The natural card number (CARD-NUM X(16)). When `undefined`
 *   or empty (for example a route with a missing `:cardNumber` param), the hook
 *   skips the network call entirely and reports `notFound`.
 * @returns A {@link UseCardDetailResult} snapshot of the current fetch state.
 */
export function useCardDetail(cardNumber: string | undefined): UseCardDetailResult {
  const [data, setData] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<unknown>(null);
  const [notFound, setNotFound] = useState<boolean>(false);

  useEffect(() => {
    // Guards every asynchronous state write so nothing is set after unmount or
    // after this run is superseded by a new `cardNumber`. React 19 double-invokes
    // effects in dev StrictMode, so this flag keeps the effect idempotent.
    let cancelled = false;

    // Guard: no card number in the route → treat as "not found", skip the call.
    if (!cardNumber) {
      setData(null);
      setError(null);
      setNotFound(true);
      setLoading(false);
      return;
    }

    // Enter the loading state; clear any prior result before the fetch.
    setLoading(true);
    setError(null);
    setNotFound(false);
    setData(null);

    // Construct the generated BFF client with the SHARED Configuration + Axios
    // instance. The interceptor on `axiosInstance` auto-attaches X-Correlation-ID
    // (via crypto.randomUUID) and Authorization: Bearer <token>, so this hook must
    // not set either header manually.
    const client = new CardDetailApi(apiConfig, undefined, axiosInstance);

    (async () => {
      try {
        // The ONE real network hop of the tracer (single-request-parameter shape).
        // xCorrelationID is intentionally omitted — the interceptor owns it.
        const res = await client.getCardDetail({ cardNumber });
        if (cancelled) return;
        setData(res.data); // AxiosResponse<CardDetail> → .data is CardDetail
      } catch (err) {
        if (cancelled) return;
        // Status mapping is exact: 404 → Empty state (card not found);
        // anything else (500 / network / unknown) → Error state.
        if (isAxiosError(err) && err.response?.status === 404) {
          setNotFound(true);
        } else {
          setError(err);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();

    // Cleanup: mark this run cancelled so a late resolution cannot write state.
    return () => {
      cancelled = true;
    };
  }, [cardNumber]);

  return { data, loading, error, notFound };
}
