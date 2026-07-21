/**
 * CardDemo - MUI (Material UI) v9 theme: the AUTHORITATIVE design vocabulary.
 *
 * No Figma designs were provided (AAP 0.5.3 / 0.9), so the tokens defined here
 * ARE the design system for the CardDemo React 19 SPA: every CSS value in
 * generated UI code must resolve to one of these tokens (only 0, none, auto,
 * inherit, currentColor, and transparent are exempt).
 *
 * Scope: @mui/material CORE only (no MUI X). This is a PURE token definition
 * (palette, typography, spacing, shape); breakpoints, the shadows elevation
 * scale, and zIndex are intentionally left at their MUI v9 defaults.
 *
 * Provenance: the legacy CICS green-screen color semantics in the BMS maps
 * [app/bms] loosely inform the palette INTENT only - the concrete values below
 * are the MUI defaults (AAP 0.5.3). Observed legacy semantics
 * ([app/bms/COCRDSL.bms], [app/bms/COSGN00.bms]):
 *   BLUE  headings/labels       -> primary  (#1976d2)
 *   RED   error message line    -> error    (#d32f2f)
 *   GREEN input / active fields -> success  (#2e7d32)
 *   YELLOW titles / TURQUOISE prompts / NEUTRAL body -> accent + default greys
 *
 * Consumed by ui/app/App.tsx via `import { theme } from './theme/theme'` and
 * applied app-wide through <ThemeProvider theme={theme}> + <CssBaseline/>.
 */
import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
  // Emit theme values as CSS variables (MUI v9 CSS-theme-variables mode). This is
  // the recommended v9 configuration: it renders tokens as `var(--mui-...)`
  // custom properties on the document, improving SSR/hydration correctness and
  // making the design tokens inspectable and overridable at the CSS layer. It is
  // fully compatible with <ThemeProvider theme={theme}> + <CssBaseline/> (m32).
  cssVariables: true,

  // 8px spacing base: theme.spacing(n) === 8 * n (MUI default, pinned explicitly).
  spacing: 8,

  // 4px corner radius (AAP 0.5.3).
  shape: {
    borderRadius: 4,
  },

  palette: {
    mode: 'light',
    // Primary actions + AppBar (legacy BLUE headings).
    primary: { main: '#1976d2' },
    // Secondary accents.
    secondary: { main: '#9c27b0' },
    // Error Alerts / error states (legacy RED ERRMSG).
    error: { main: '#d32f2f' },
    // Active-status Chip on Card Detail (legacy GREEN input/active fields).
    success: { main: '#2e7d32' },
    // background.default / background.paper and text.primary / text.secondary are
    // intentionally left at their MUI light-mode defaults (AAP 0.5.3):
    //   background.default = #fff, background.paper = #fff,
    //   text.primary  ~ rgba(0, 0, 0, 0.87), text.secondary ~ rgba(0, 0, 0, 0.6).
  },

  typography: {
    // Roboto-first stack. The Roboto webfont (weights 300/400/500/700) is
    // SELF-HOSTED via @fontsource/roboto, imported as side-effect CSS in
    // ui/app/App.tsx (finding P4-m01) - no external Google Fonts <link> is used.
    fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
    fontWeightLight: 300,
    fontWeightRegular: 400,
    fontWeightMedium: 500,
    fontWeightBold: 700,
  },

  // P5-A11Y-01: component-level MINIMUM touch/hit areas. The primary interactive
  // controls (Sign On / Sign Out `Button`s and the mobile navigation hamburger
  // `IconButton`) previously rendered below the WCAG 2.5.5 44x44px target size.
  // Enforcing the minimum here — once, at the theme layer — fixes every instance
  // app-wide (per the finding's "enforce through MUI theme/component tokens").
  // The 44px value is expressed as `theme.spacing(5.5)` (8px base x 5.5 = 44px)
  // so it still resolves to the design-system spacing scale rather than a
  // hardcoded pixel literal (AAP §0.5.5 "no hardcoded values").
  components: {
    MuiButton: {
      styleOverrides: {
        root: ({ theme }) => ({
          minHeight: theme.spacing(5.5),
        }),
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: ({ theme }) => ({
          minWidth: theme.spacing(5.5),
          minHeight: theme.spacing(5.5),
        }),
      },
    },
  },

  // MUI defaults, intentionally NOT overridden (documented for reference):
  //   breakpoints: xs 0 / sm 600 / md 900 / lg 1200 / xl 1536
  //   shadows:     the 25-step (0..24) MUI elevation scale
  //   zIndex:      appBar 1100 / drawer 1200 / modal 1300
  // consumed by the Header / NavMenu / Drawer layout components.
});
