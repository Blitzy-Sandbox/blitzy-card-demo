import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  type ReactNode,
} from 'react';
import { AuthApi, type LoginRequest } from '../api/generated';
import { apiConfig, axiosInstance } from '../api/correlationId';
import { authStore, useAuthToken } from './authStore';

/** Shape of the authentication context exposed via {@link useAuth}. */
export interface AuthContextValue {
  token: string | null;
  isAuthenticated: boolean;
  login: (userId: string, password: string) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  // Token is derived LIVE from the synchronized {@link authStore} (backed by
  // sessionStorage) rather than a lazy useState copy that could drift out of sync
  // with sessionStorage (finding P5-SEC-01). No router hooks are used here — the
  // provider is intentionally mounted ABOVE <BrowserRouter> (see App.tsx).
  const token = useAuthToken();

  const login = useCallback(async (userId: string, password: string): Promise<void> => {
    const authApi = new AuthApi(apiConfig, undefined, axiosInstance);
    const loginRequest: LoginRequest = { userId, password };
    const response = await authApi.login({ loginRequest });
    // Persist through the store so every subscriber (guard, header, sign-on)
    // re-syncs immediately.
    authStore.setToken(response.data.token);
  }, []);

  const logout = useCallback((): void => {
    authStore.clear();
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ token, isAuthenticated: token !== null, login, logout }),
    [token, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  // Read the token from the synchronized store HERE (not only from the context
  // value) so consumers that re-render on navigation — notably RouteGuard via
  // useLocation — observe the LIVE sessionStorage token. AuthProvider sits above
  // the router and does NOT re-render on client-side navigation, so relying on the
  // context-carried token alone would let a token cleared mid-session go unnoticed
  // until a full reload (finding P5-SEC-01). This adds no router hook to the
  // provider; it simply re-derives the freshest token at the point of use.
  const token = useAuthToken();
  return { ...context, token, isAuthenticated: token !== null };
}
