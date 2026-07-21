import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Box, Button, Container, Paper, Stack, TextField, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../app/auth/AuthProvider';
import { Error as ErrorState } from '../../components/Error';

/**
 * SignOn - permissive Sign-On screen for the PUBLIC route `/signon`.
 * [SRC: COSGN00.bms] Legacy CICS sign-on screen (COSGN00): User ID (8 char),
 * Password (8 char, masked), branding line, "Type your User ID and Password",
 * and the "ENTER=Sign-on" action.
 *
 * The token hop is REAL (UI -> BFF POST /api/auth/login -> auth-svc) but
 * credential validation is a permissive stub. The UI binds ONLY to the BFF:
 * this screen calls `useAuth().login()` (which wraps the generated AuthApi,
 * stores the token, and updates context) - never a domain service or the
 * generated client directly.
 *
 * Accessibility / validation:
 *  - P6-A11Y-01: the screen is wrapped in a single `main` landmark
 *    (`Container component="main"`) while retaining its single H1.
 *  - P6-FORM-01: required-field validation runs on submit BEFORE any network
 *    call. Empty fields block the POST, surface a per-field message (which makes
 *    MUI mark the input `aria-invalid` and link the helper text), and move focus
 *    to the first invalid field. Editing a field clears its message and any stale
 *    server-error banner so feedback never lingers while typing.
 */
export default function SignOn() {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // P6-FORM-01: per-field validation messages (null = valid). When set they drive
  // each TextField's `error` + `helperText`, so MUI exposes `aria-invalid=true`
  // and an `aria-describedby` helper text, and they gate submission below.
  const [userIdError, setUserIdError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  // Refs to the underlying <input>s so the FIRST invalid field receives focus on a
  // blocked submit (accessible error recovery).
  const userIdRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  /** Required-field check; returns a message when empty (after trim), else null. */
  const requiredMessage = (label: string, value: string): string | null =>
    value.trim() === '' ? `${label} is required` : null;

  const handleSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    // Clear any stale server-side error banner from a previous attempt.
    setError(null);

    // Validate ALL required fields up front so every missing field is surfaced at
    // once (not just the first). Empty fields block the POST entirely.
    const nextUserIdError = requiredMessage('User ID', userId);
    const nextPasswordError = requiredMessage('Password', password);
    setUserIdError(nextUserIdError);
    setPasswordError(nextPasswordError);

    if (nextUserIdError || nextPasswordError) {
      // Move focus to the first invalid field for accessible recovery, and do NOT
      // call login() — the request is never sent.
      if (nextUserIdError) {
        userIdRef.current?.focus();
      } else {
        passwordRef.current?.focus();
      }
      return;
    }

    setSubmitting(true);
    try {
      await login(userId, password);
      navigate('/menu');
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  // Editing a field clears its own validation message AND any stale server-error
  // banner, so feedback never persists while the user is correcting input.
  const handleUserIdChange = (value: string): void => {
    setUserId(value);
    if (userIdError) setUserIdError(null);
    if (error) setError(null);
  };
  const handlePasswordChange = (value: string): void => {
    setPassword(value);
    if (passwordError) setPasswordError(null);
    if (error) setError(null);
  };

  return (
    <Container maxWidth="sm" component="main">
      <Box
        sx={{
          minHeight: '100vh',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          py: 4,
        }}
      >
        <Paper elevation={3} sx={{ p: 4, width: '100%' }}>
          <Stack component="form" spacing={3} onSubmit={handleSubmit} noValidate>
            <Box sx={{ textAlign: 'center' }}>
              <Typography variant="h5" component="h1" gutterBottom>
                Credit Card Demo Application for Mainframe Modernization
              </Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Type your User ID and Password
              </Typography>
            </Box>

            {error ? <ErrorState error={error} /> : null}

            <TextField
              label="User ID"
              value={userId}
              onChange={(event) => handleUserIdChange(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 8 } }}
              inputRef={userIdRef}
              error={Boolean(userIdError)}
              helperText={userIdError ?? undefined}
              autoComplete="username"
              autoFocus
              fullWidth
              required
              disabled={submitting}
            />
            <TextField
              label="Password"
              type="password"
              value={password}
              onChange={(event) => handlePasswordChange(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 8 } }}
              inputRef={passwordRef}
              error={Boolean(passwordError)}
              helperText={passwordError ?? undefined}
              autoComplete="current-password"
              fullWidth
              required
              disabled={submitting}
            />

            <Button type="submit" variant="contained" fullWidth disabled={submitting}>
              Sign On
            </Button>
          </Stack>
        </Paper>
      </Box>
    </Container>
  );
}
