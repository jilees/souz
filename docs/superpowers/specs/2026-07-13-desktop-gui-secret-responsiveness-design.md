# Desktop GUI Secret Responsiveness Design

## Context

Desktop navigation profiling showed repeated 0.5–1.1 second stalls on the AWT event-dispatch thread when entering Settings or returning to Main. The dominant stack was:

```text
SettingsProvider.hasKey
  -> ConfigStore.get
  -> SecretPrefsCodec.decodeForRead
  -> AesGcmSecretCodec.deriveKey
  -> PBKDF2WithHmacSHA256 (600,000 iterations)
```

The same encrypted provider key is currently read once per model during availability filtering. Settings also reads every provider key into `SettingsState` during refresh, even when the user never opens or reveals the key field. Both desktop and Android share the Settings ViewModel and key field presentation contracts, so the behavior must remain coherent across the two hosts.

## Goals

- Keep synchronous main-thread work caused by navigation below 50 ms, with visible response within 100 ms.
- Make model and voice-provider availability checks independent of secret decryption.
- Do not decrypt stored provider keys when Settings opens.
- Show a stored key as a fixed-length mask and decrypt only after the user presses the field's visibility icon.
- After reveal, preserve the existing editable field behavior: typing replaces the key and clearing it removes the key.
- Remove revealed plaintext from UI state when the field is hidden or Settings closes.
- Preserve encrypted storage and the existing PBKDF2 work factor.
- Keep common composables presentation-only and keep storage, decryption, and coroutine coordination in platform settings providers and `SettingsViewModel`.

## Non-goals

- Migrating desktop secrets to macOS Keychain.
- Caching plaintext secrets for the lifetime of the application.
- Changing provider authentication protocols or Codex OAuth behavior.
- Optimizing the separate full-screen offscreen-rendering or local-model-healthcheck candidates. Those require a dedicated frame-time design after the confirmed crypto stalls are removed.
- Redesigning the Settings layout or adding separate Edit, Save, or Delete buttons.

## Responsiveness budgets

- Discrete navigation must not perform more than 50 ms of synchronous work on the UI thread.
- A navigation action must produce a visible response within 100 ms.
- Animation-frame work should target the 16.7 ms budget of a 60 Hz display.
- Secret reveal and secret persistence may take longer, but must run outside the UI thread and expose a non-blocking progress state.

These limits follow Apple responsiveness guidance for discrete interaction and display refresh intervals and the Android 16 ms slow-frame guideline.

## Architecture

### 1. Presence checks do not decrypt

`SettingsProvider` will expose provider-presence operations for LLM and voice providers. The existing `hasKey` extensions become interface behavior with default implementations for test fakes and compatibility. Production implementations override them without reading secret values:

- Desktop `SettingsProviderImpl` checks `ConfigStore` raw preference presence plus non-blank environment/system-property overrides.
- Android `AndroidSettingsProvider` checks `SharedPreferences.contains` for stored values.
- Local providers remain available without a key.
- Codex presence checks use token storage presence without decrypting the token.

`ConfigStore` gains a raw `contains(key)` operation. It must not call `SecretPrefsCodec`, migrate plaintext, derive a key, or validate the encrypted payload. Blank writes continue to remove the preference, so raw presence has the same meaning as “configured” for values written by current code.

Model availability collects configured providers once and filters models against that set. It must not call a provider secret getter. This removes the current per-model multiplication of PBKDF2 work.

### 2. API-key field state never uses the mask as a secret value

The six provider-key strings in `SettingsState` are replaced by field states keyed by `ApiKeyField`. The ViewModel state is:

```kotlin
sealed interface ApiKeyFieldState {
    data object StoredHidden : ApiKeyFieldState
    data object Revealing : ApiKeyFieldState
    data class Editable(
        val value: String,
        val revealed: Boolean,
    ) : ApiKeyFieldState
    data object RevealFailed : ApiKeyFieldState
}
```

State meaning:

- Missing key: `Editable(value = "", revealed = false)`. The user can enter a new key immediately, with password transformation matching current behavior.
- Stored key: `StoredHidden`. No plaintext is present in the ViewModel.
- Reveal in progress: `Revealing`. The field remains masked and read-only while showing progress.
- Successful reveal: `Editable(value = decrypted, revealed = true)`. The field behaves exactly like the current editable field.
- Failed reveal: `RevealFailed`. The field becomes an empty editable replacement field after displaying an error; the stored value is not removed until the user actually changes or clears the field.

The fixed mask is created only by the UI mapper. It is never assigned to `SettingsState`, `pendingKeyDrafts`, or `SettingsProvider`, so it cannot be persisted accidentally.

### 3. One visibility control, no edit-mode controls

`SharedApiKeyFieldUi` gains explicit mode information. `SharedKeysSettingsContent` renders one trailing visibility icon for each provider-key field:

- `StoredHidden`: fixed-length mask, read-only field, “show” icon.
- `Revealing`: fixed-length mask, read-only field, progress indicator.
- `Editable(revealed = true)`: plaintext, editable field, “hide” icon.
- `Editable(revealed = false)`: password-transformed editable field, “show” icon. Because the value is already a user-entered draft, showing it does not require storage access.

`LabeledTextField` gains presentation-only parameters for `readOnly` and trailing content. It continues to emit `onValueChange` only when editable.

The desktop shared Settings content and the Android Settings host consume the same state and visibility event. No Edit, Save, or Delete button is introduced.

### 4. Reveal and persistence data flow

The shared UI emits a generic key-visibility event containing the `ApiKeyField` identifier. `SettingsViewModel` handles it as follows:

```text
StoredHidden
  -> Revealing
  -> background read of exactly one provider property
  -> Editable(decrypted, revealed = true)

Editable(revealed = false)
  -> Editable(same value, revealed = true)

Editable(revealed = true)
  -> flush that field's pending save if modified
  -> clear plaintext state
  -> StoredHidden when persisted, otherwise Editable("", revealed = false)
```

PBKDF2 is CPU-bound. Reveal and encryption therefore run on an injectable bounded computation dispatcher based on `Dispatchers.Default.limitedParallelism(1)`, not the AWT dispatcher and not an unbounded IO pool. Tests inject a coroutine-test dispatcher.

Existing 400 ms deferred persistence remains. Saving a key updates only key presence, configured count, and provider-dependent model lists; it must not run a full Settings refresh that replaces the actively edited field. A blank value removes the preference. Saving must preserve the current visible/editable state until the user hides the field or leaves Settings.

### 5. Screen lifecycle and refresh behavior

Settings initializes secret fields solely from presence checks. Its normal refresh must not read provider-key properties.

The duplicate first-entry refresh is removed: `SettingsViewModel` retains its initialization refresh, while desktop and Android Settings routes stop sending a second unconditional `RefreshFromProvider` from `LaunchedEffect`.

Every Settings close path is routed through `SettingsEvent.GoToMain` so the ViewModel can:

1. immediately mask every revealed field and expose a non-blocking closing progress state when a save is pending;
2. flush pending text and key saves on their background dispatchers;
3. clear plaintext draft maps;
4. emit `SettingsEffect.CloseScreen`.

The current two-second debounce on the complete Settings effects stream is removed. Save debouncing remains local to text/key persistence and does not delay navigation effects.

Main continues to refresh model selection on re-entry, but its availability calculation uses presence checks only. Returning from Settings or Memory therefore performs no secret decryption.

### 6. Error handling

- Reveal failure produces a user-visible, provider-neutral error and a technical log without secret material.
- A failed reveal leaves storage untouched.
- The field becomes an empty replacement editor, allowing recovery without adding an Edit button.
- Cancellation restores `StoredHidden` and never exposes a partial value.
- If an environment/system-property key is configured, presence remains true after a UI attempt to clear the preference because the external override still exists.
- Closing Settings waits for already-entered deferred values to persist off the UI thread. Fields are masked and closing progress is visible immediately, so this exceptional path remains responsive even if encryption exceeds 100 ms.

## Testing strategy

Tests are written before each production change.

### Shared logic

- `SecretPrefsCodecTest`: raw `ConfigStore.contains` reports presence for an encrypted or malformed stored payload without decoding it; removal reports absence.
- Settings-provider tests: production presence checks honor stored values and environment/system-property fallbacks without invoking secret getters.
- Android compilation verifies the raw SharedPreferences presence implementation.

### Shared UI logic

- `ModelAvailabilityTest`: availability uses provider-presence methods and never reads secret properties; repeated models do not repeat an expensive presence query per model.
- `SettingsViewModelTest`:
  - initialization produces `StoredHidden` for configured keys without reading their values;
  - toggling one stored key reads exactly that key once and reveals it;
  - other provider keys are not read during reveal;
  - an absent key remains editable without storage access;
  - editing and clearing a revealed key preserve current deferred-save semantics;
  - hiding and closing clear plaintext from state;
  - reveal failure allows replacement without deleting the stored key;
  - closing flushes pending changes before emitting `CloseScreen`;
  - model normalization still selects valid models using presence data.
- Shared UI mapper tests verify that only `StoredHidden` receives the fixed mask and read-only mode.

### Regression and acceptance verification

- Run focused `:sharedLogic:test` and `:sharedUI:jvmTest` suites after every task.
- Compile desktop and Android shared consumers.
- Launch the desktop application and repeat Main → Settings → Main, Main → Memory → Main, and Tools → Settings at least five times.
- Capture a new 30–45 second JFR profile. With no reveal/save action, there must be zero `AesGcmSecretCodec` or PBKDF2 samples under `AWT-EventQueue-0`.
- Navigation must show no continuous UI-thread busy interval above 50 ms attributable to application settings code and must visibly respond within 100 ms on the profiling Mac.

## Rollout boundary

This implementation addresses the confirmed crypto-driven stalls for Main, Settings, Memory → Main, and Tools → Settings. If Main → Memory still misses the responsiveness target after this change, the next design will isolate frame timing for `AnimatedContent`, full-screen offscreen compositing, and local-model availability checks rather than mixing speculative rendering changes into this fix.
