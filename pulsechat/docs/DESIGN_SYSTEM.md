# PulseChat design system — "Graphite Titanium"

Source of truth: `app/src/main/java/com/example/ui/theme/` (`Color.kt`, `Theme.kt`, `Type.kt`, `Tokens.kt`).
Screens take colours from `MaterialTheme.colorScheme` or `PulseTheme.colors` and sizes from `Spacing` /
`Radii` — never from local hex values or one-off dp numbers.

## Palette

| Role | Dark | Light | Used for |
|---|---|---|---|
| background | `#0B0F14` | `#F4F6F8` | window, screens |
| surface | `#0F151C` | `#FFFFFF` | top bars, lists, settings |
| surfaceContainer (low → highest) | `#121A23` → `#223041` | white → `#E9EEF3` | sheets, menus, dialogs, fields |
| outlineVariant / outline | `#2B3948` / `#627385` | `#D7E0E8` / `#7F90A1` | dividers / field borders |
| text / secondary text | `#F1F5F9` / `#A5B2C2` | `#18212B` / `#5E6B79` | |
| primary (accent) | `#5B9BD5` | `#326A9A` | actions, selection, links outside bubbles |
| secondary (teal) | `#35B7A5` | `#137E73` | sparing positive accents |
| tertiary (assistant) | `#4CC2D1` | `#0E7480` | everything AI (badge, banners) |
| secondaryContainer | `#233547` | `#E1E9F1` | selected chips, navigation indicator |
| error / success / warning | `#F87171` / `#3FB950` / `#E3B341` | `#C62828` / `#1E7B3A` / `#8A5A00` | states |

Chat (`PulseTheme.colors`): outgoing bubble `#244D6B` / `#D8E7F4`, incoming `#1B2633` / white, assistant
`#133038` / `#E3F3F5`, each with its own text, secondary-text and link colour; read ticks `#6CCFF6` /
`#1F78B4`; sender names from an 8-colour palette readable on every bubble.

The call screen (`CallPalette`) is always dark. Avatars (`AvatarPalette`) are muted mid-tones under a white
initial; colours stored by older versions (indigo/violet) are mapped when drawn, the stored value is kept.

Every Material role is set explicitly — unset roles fall back to Material's purple baseline.
`ThemeContrastTest` fails the build when a text pair drops below 4.5:1 (icons/borders 3:1).

## Tokens

- Spacing (4dp grid): `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 20 · xxl 24 · xxxl 32`; screen edge 16;
  touch target 48; control height 52; content max width 560 (tablets); bubble max width 520.
- Radii: `xs 6 · sm 10 · md 14 · lg 18 · xl 26`; bubbles 18 with a 6dp corner toward the sender.
- Shapes: fields/menus/snackbars 8 · chips 10 · cards 14 · FAB 18 · dialogs/sheets 26.
- Type: system fonts (Roboto / platform Arabic). Headline 22–30 SemiBold, title 14–20 SemiBold/Medium,
  body 12–16, label 11–14 Medium. Letter spacing ≤ 0.2sp (wide tracking breaks up Arabic).
  Messages use `MessageTextStyle` (15.5/21).
- Elevation: flat surfaces separated by tone or a hairline; FAB 3dp; no glow, gradients or glass.

## Motion

| Token | Duration | Used for |
|---|---|---|
| `Motion.QUICK` | 120 ms | icon swaps (mic ↔ send), toggles, jump-to-latest button |
| `Motion.STANDARD` | 180 ms | panels, reply bar, call state line, new message fade-in |
| `Motion.NAVIGATION` | 220 ms | screen transitions (fade + 1/10 slide, direction-aware) |

Rules: motion confirms an action and never delays it; nothing loops unless it reflects a real state
(ringing call, active recording) and only while that screen is visible; list items fade in once and
never slide; Compose scales every duration with the system animator scale, and decorative loops
check `rememberReducedMotion()` so "Remove animations" turns them off.

## Insets

Edge-to-edge everywhere. A screen applies each inset once: top bars handle the status bar; the chat
composer area applies `navigationBars ∪ ime` (whichever is taller); scrolling screens add the
navigation-bar inset at the end of their content. System-bar icons follow the in-app theme;
always-dark screens call `LightSystemBarIcons()`.

## Layout review

`ScreenshotTourTest` (instrumented) drives the real app on the CI emulator at 360dp, 320dp with 130%
text, 411dp dark Arabic (RTL) and an 800dp tablet, and CI publishes the screenshots as check runs
("Screenshot …"). Use it when changing layouts — problems often show on only one size.
