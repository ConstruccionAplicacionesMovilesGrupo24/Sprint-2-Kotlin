# Design system alignment with the CampusMeal UI Figma file

Source: [CampusMealUI](https://www.figma.com/design/n5XwiFX9JwaGvr9pkhoO0L/CampusMealUI), frame
**Fundamentos** (`27:280`) for tokens, and screens **04 Permiso de ubicación** (`20:68`),
**13 Ubicación concedida** (`35:280`) and **14 Ubicación denegada** (`35:330`) for the location flow.

Scope of this pass: the design system plus the location screens. Home, the app bar and bottom
navigation components, and the Set Context form (time, budget, dietary preferences, delivery) are
not covered yet.

## Tokens

| File | What changed |
| --- | --- |
| `core/designsystem/Color.kt` | The temporary green palette is replaced by the Figma primitives: Brand (orange), Accent (yellow), Sand, Neutrals and Positive ramps, with exact hex values. `CampusMealColors` adds the semantic tokens Material 3 has no slot for: canvas, surface, border, text, and the positive, warning and urgent pairs. |
| `core/designsystem/Type.kt` | Inter replaces the platform sans-serif. The 11 Figma styles (Display/L to Caption) are mapped onto Material 3 styles; the mapping table is in the file's KDoc. |
| `core/designsystem/Theme.kt` | Material 3 light scheme built from the primitives (primary Brand 500, background Neutral 50, outline Sand 300…). The extra tokens are exposed as `MaterialTheme.campusMealColors`. |
| `res/font/inter_*.ttf` | Inter 4.1 Regular, Medium, SemiBold and Bold (about 1.7 MB). Licence in `android/licenses/Inter-OFL.txt`. |

Screens should use `MaterialTheme.colorScheme`, `MaterialTheme.typography` and
`MaterialTheme.campusMealColors`, never the primitives directly.

Dark theme: the Figma file only delivers light. The dark scheme re-points the same ramps as a
prototype approximation and must be revisited once dark mode is designed.

## Location screens

`feature/context/LocationContextScreen.kt` now follows the three screens:

- **04:** app bar "Location", concentric location mark, "Better results with your location", the
  privacy copy, "Enable location" and "Choose campus manually".
- **13:** "Location enabled" positive banner with campus and accuracy, and the location card with
  "Change".
- **14:** "No location access" warning banner (triangle mark), location card and Uniandes /
  Javeriana / Nacional chips. The banner stays visible after a campus is picked.

`CampusCatalog` now uses the design's campus names (Uniandes, Javeriana, Nacional).

Deliberate deviations:

- **"Continue with limited results" is not implemented.** BQ4 requires at least one location
  source (coordinates or campus), so continuing with neither would build an invalid search request.
  Decide with design whether it should be removed or mean something else.
- The back mark is a text chevron and the check mark in chips is a character, to avoid adding the
  Material icons dependency to the prototype.
- The location card has no "Choose" action in the denied state: the chips right below it already
  do that.

## Validation

`assembleDebug` and `lintDebug` pass (0 errors, the same 5 pre-existing version warnings). Checked on
the `Tusky_API_36` emulator: screen 04; screen 13 after granting approximate location; screen 14 after
denying, then picking Javeriana and Nacional.
