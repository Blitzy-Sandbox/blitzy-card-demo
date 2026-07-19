/**
 * PageContainer — standard page frame for the CardDemo UI.
 *
 * A pure presentational layout wrapper from the shared `ui/components` library.
 * It provides the consistent page frame used by every feature screen — the live
 * Card Detail tracer and all deferred placeholder screens alike — rendering a
 * width-constrained, padded content region with an optional header row that
 * carries a title, a subtitle, and right-aligned actions.
 *
 * Design-system rules (AAP §0.5, non-negotiable): this component is composed
 * exclusively from `@mui/material` core primitives (`Container`, `Stack`,
 * `Box`, `Typography`); every spacing value resolves to a theme spacing token
 * (`py`, `mb`, `spacing`); there are no hardcoded px values, no hardcoded
 * colors, and no raw DOM layout/heading elements. It performs NO data fetching
 * and holds NO business logic.
 *
 * Provenance: [SRC: COCRDSLC | CARDDAT] — generalizes the legacy 24×80 screen
 * frame (title line + body + message/footer), e.g. the Card Detail map
 * `app/bms/COCRDSL.bms` ("View Credit Card Detail" title at L78). REFERENCE
 * only; no legacy fields are ported into this generic, reusable wrapper.
 */
import type { ReactNode } from 'react';
import { Box, Container, Stack, Typography } from '@mui/material';
import type { ContainerProps, SxProps, Theme } from '@mui/material';

/**
 * Props for {@link PageContainer}.
 *
 * Every member except `children` is optional. When none of `title`, `subtitle`,
 * or `actions` are supplied, the header row is omitted entirely and only the
 * padded body region is rendered — keeping body-only screens free of empty
 * header spacing.
 */
export interface PageContainerProps {
  /** Optional page title rendered as an h1-semantic Typography heading. */
  title?: string;
  /** Optional secondary/subtitle line rendered beneath the title. */
  subtitle?: string;
  /** Optional right-aligned actions (buttons, etc.) shown on the title row. */
  actions?: ReactNode;
  /** Container max-width constraint. Defaults to `'lg'`. */
  maxWidth?: ContainerProps['maxWidth'];
  /** Page body content. */
  children: ReactNode;
  /** `sx` passthrough merged onto the outer Container (theme tokens only). */
  sx?: SxProps<Theme>;
}

/**
 * Standard page wrapper composed from MUI core layout primitives.
 *
 * @param props - See {@link PageContainerProps}.
 * @returns The framed page: an optional header row (title / subtitle / actions)
 *          above a padded body region, constrained and centered by `Container`.
 */
export function PageContainer({
  title,
  subtitle,
  actions,
  maxWidth = 'lg',
  children,
  sx,
}: PageContainerProps) {
  // Render the header row only when at least one header element is provided, so
  // body-only screens (e.g. simple deferred placeholders) carry no empty band.
  const hasHeader = Boolean(title || subtitle || actions);

  return (
    <Container
      maxWidth={maxWidth}
      // Merge the default vertical page padding with any caller-provided `sx`
      // using MUI's array-merge form so array/callback `sx` values are honored
      // (object spread would silently drop those SxProps shapes). All values
      // resolve to theme spacing tokens — no hardcoded px.
      sx={[{ py: 3 }, ...(Array.isArray(sx) ? sx : [sx])]}
    >
      {hasHeader ? (
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          sx={{
            mb: 3,
            alignItems: { sm: 'center' },
            justifyContent: 'space-between',
          }}
        >
          <Box>
            {title ? (
              <Typography variant="h4" component="h1">
                {title}
              </Typography>
            ) : null}
            {subtitle ? (
              <Typography variant="body2" color="text.secondary">
                {subtitle}
              </Typography>
            ) : null}
          </Box>
          {actions ? <Box>{actions}</Box> : null}
        </Stack>
      ) : null}
      <Box>{children}</Box>
    </Container>
  );
}
