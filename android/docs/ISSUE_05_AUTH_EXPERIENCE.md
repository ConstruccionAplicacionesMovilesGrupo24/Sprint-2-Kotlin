# Issue #5 — Authentication experience (Juan Pablo)

This document covers **Person A (Juan Pablo)'s part of Issue #5**: the Login, Registration and
Session Expired screens, `AuthViewModel`, field validation and protected navigation. Person B
(Natalia) owns the authentication data layer and session security.

## Files

| File | Main types | Responsibility |
| --- | --- | --- |
| `feature/auth/domain/AuthRepository.kt` | `AuthRepository`, `SessionStatus`, `AuthResult` | **Contract** between the screens and the data layer. |
| `feature/auth/data/NetworkAuthRepository.kt` | `NetworkAuthRepository`, `AuthApi`, DTOs, `SessionExpiryInterceptor` | **Interim** data layer so the screens run end to end. Natalia replaces it. |
| `feature/auth/AuthViewModel.kt` | `AuthViewModel`, `AuthUiState`, `AuthForm`, `FieldError` | Form state, validation, the eight UI states. |
| `feature/auth/AuthScreens.kt` | `LoginScreen`, `RegistrationScreen`, `SessionExpiredDialog` | Figma screens 01, 02 and 20. |
| `app/CampusMealApp.kt` (modified) | `CampusMealApp` | Picks the signed-out or the protected graph from `sessionStatus`. |
| `navigation/CampusMealNavHost.kt`, `CampusMealRoute.kt` (modified) | `AuthNavHost`, `Register` | Signed-out graph; temporary "Log out" button on Home. |
| `app/AppContainer.kt`, `core/network/ApiClientFactory.kt` (modified) | `authRepository`, `interceptors` | Wiring, and room for interceptors before logging. |

## Contract for Natalia

The screens only depend on `AuthRepository`. Replace `NetworkAuthRepository` with the full layer
and keep these guarantees:

- `sessionStatus` emits `AUTHENTICATED` while tokens exist, `SIGNED_OUT` otherwise, and `EXPIRED`
  after `expireSession()` until `acknowledgeExpiredSession()` or a new login.
- `login()` / `register()` return `InvalidCredentials` for 401, `EmailAlreadyRegistered` for 409,
  `RejectedInput` for 400/422, and `Unavailable` for no connection or 5xx.
- `expireSession()` is called once the single refresh attempt has failed. Today
  `SessionExpiryInterceptor` calls it on any 401 from an authenticated, non-`auth/` request —
  the refresh step belongs just before it.
- `logout()` always removes the local session, even if the backend is unreachable.

Not covered by the interim layer, and still Natalia's: refresh, coordination of simultaneous
401s, Keystore-backed storage (tokens are still in `InMemorySessionStorage`, so a restart signs the
user out), `GET me` and session restoration at start.

The provisional contract assumes `auth/login` and `auth/register` both return
`{ "accessToken", "refreshToken" }`, and `auth/logout` takes the bearer header plus
`{ "refreshToken" }`.

## UI states

| State | Meaning |
| --- | --- |
| `Initial` | Nothing submitted, or the user edited a field after a message. |
| `Validating` | The last submit failed client-side validation; each field shows its own error. |
| `Loading` | Request in flight; the button shows a spinner and ignores taps. |
| `Authenticated` | Session saved; `CampusMealApp` switches to the protected graph. |
| `InvalidCredentials` | Wrong email or password. |
| `RegistrationError` | Email already registered, or fields rejected by the backend. |
| `ConnectionError` | No connection or service down — worded and coloured differently from credential errors. |
| `SessionExpired` | Login opened from the Session Expired screen. |

## Validation

- **Email:** required and `name@domain.tld` shaped. The `.edu.co` domain is not enforced.
- **Password (registration):** at least 8 characters with letters and digits. Login only checks it
  is not empty, so older passwords still work.
- **Confirmation:** must match the password.
- **Name and terms:** name required; terms must be accepted.

Errors are the field's supporting text, so TalkBack reads them with the field. Authentication
messages are a live region. The terms row toggles as a whole, so its label belongs to the checkbox.

## Security

- Passwords live only in the ViewModel's memory, go only into the request body, and are cleared
  after success. `AuthForm.toString()` and `TokenResponseDto.toString()` redact secrets.
- Debug HTTP logging stays at `HEADERS` with `Authorization` redacted; bodies, which carry passwords
  and tokens, are never logged.
- Protected navigation: the main graph is only composed while a session exists. Logging out or
  expiring discards it, so the back stack cannot reopen a protected screen.

## Deliberate deviations from the design

- **"Forgot password?"** (screen 01) and **"Continue offline"** (screen 20) are not implemented:
  there is no backend contract for password reset, and continuing offline would contradict
  "protected screens cannot be opened without a valid session".
- "Log out" is a temporary button on the Home placeholder until the Profile screen exists.

## Validation

No automated tests (prototype rules). `assembleDebug` and `lintDebug` pass with 0 errors; the test
sources re-added in the branch still compile.

Checked by hand on the `Tusky_API_36` emulator against a throwaway local stub of the five
endpoints (not part of the repository):

| Path | Result |
| --- | --- |
| App start without a session | Opens Login; Home is not reachable. |
| Empty submit / bad email | "This field is required." / "Enter a valid email address." |
| Backend unreachable | Yellow "We could not reach CampusMeal…" banner. |
| Wrong password (401) | Separate "The email or password is incorrect." banner. |
| Registration mismatch, terms | "Passwords do not match.", "Accept the terms…" |
| Existing email (409) | "An account with this email already exists." |
| Successful registration and login | Protected Home opens. |
| Log out | Back to Login; `auth/logout` called. |
| Authenticated request rejected (401) | Session Expired modal; back does not dismiss it. |
| "Log in" on the modal | Login with the expired notice; logging in returns to Home. |
| Logcat after the whole run | No password, access token or refresh token; `Authorization` shows as `██`. |

## Pending

- `CampusMealAppTest` (instrumented, re-added in this branch) expects Home on launch. With
  protected navigation the app now opens Login, so that test needs updating by whoever keeps it.
