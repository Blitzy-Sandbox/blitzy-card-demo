import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { AuthApi, type LoginRequest } from '../api/generated';
import { apiConfig, axiosInstance } from '../api/correlationId';

/**
 * sessionStorage key holding the bearer token. This provider OWNS writing and
 * clearing it. It MUST stay in lockstep with ui/app/api/correlationId.ts, whose
 * request interceptor only READS this key. The two modules are intentionally
 * decoupled and share only this literal string (no cross-import).
 */
const TOKEN_STORAGE_KEY = 'carddemo.token';

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
  const [token, setToken] = useState<string | null>(() =>
    sessionStorage.getItem(TOKEN_STORAGE_KEY),
  );

  const login = useCallback(async (userId: string, password: string): Promise<void> => {
    const authApi = new AuthApi(apiConfig, undefined, axiosInstance);
    const loginRequest: LoginRequest = { userId, password };
    const response = await authApi.login({ loginRequest });
    const issuedToken = response.data.token;
    sessionStorage.setItem(TOKEN_STORAGE_KEY, issuedToken);
    setToken(issuedToken);
  }, []);

  const logout = useCallback((): void => {
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    setToken(null);
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
  return context;
}
