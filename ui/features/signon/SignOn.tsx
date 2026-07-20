import { useState } from 'react';
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
 */
export default function SignOn() {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(userId, password);
      navigate('/menu');
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Container maxWidth="sm">
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
              onChange={(event) => setUserId(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 8 } }}
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
              onChange={(event) => setPassword(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 8 } }}
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
