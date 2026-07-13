# Desktop GUI Secret Responsiveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove provider-key PBKDF2 work from desktop navigation and decrypt an individual Settings key only after the user presses its visibility icon, while preserving the current editable field behavior after reveal.

**Architecture:** Production settings providers expose O(1) raw-presence checks that never decrypt secrets. `SettingsViewModel` stores a per-field state machine instead of eagerly loaded key strings and delegates bounded background reveal/persistence to a focused use case. Shared desktop/Android presentation renders one visibility control and never passes the fixed mask to persistence.

**Tech Stack:** Kotlin Multiplatform/JVM, Compose Multiplatform Material 3, coroutines, Kodein DI, kotlin.test, MockK, Gradle, JFR.

## Global Constraints

- Write and observe a failing test before each production behavior change.
- Keep synchronous navigation work on the UI thread below 50 ms and produce visible response within 100 ms.
- Preserve AES-GCM storage and `PBKDF2_ITERATIONS = 600_000`.
- Do not keep a process-lifetime plaintext key cache.
- Do not add Edit, Save, or Delete buttons; the visibility icon is the only new control.
- Keep common composables presentation-only; storage, decryption, and coroutine coordination stay in providers, use cases, and ViewModels.
- Use `Dispatchers.Default.limitedParallelism(1)` through an injectable dispatcher for CPU-bound reveal/encryption work.
- Delete `docs/superpowers/specs/2026-07-13-desktop-gui-secret-responsiveness-design.md` before final delivery.
- Do not change the GPU/offscreen-rendering or local-model-healthcheck paths in this implementation.

---

### Task 1: Non-decrypting provider-key presence

**Files:**
- Modify: `sharedLogic/src/jvmMain/kotlin/ru/souz/db/ConfigStore.kt`
- Modify: `sharedLogic/src/commonJvmMain/kotlin/ru/souz/db/SettingsProvider.kt`
- Modify: `sharedLogic/src/jvmMain/kotlin/ru/souz/db/SettingsProviderImpl.kt`
- Modify: `sharedLogic/src/androidMain/kotlin/ru/souz/android/settings/AndroidSettingsProvider.kt`
- Test: `sharedLogic/src/test/kotlin/ru/souz/db/SecretPrefsCodecTest.kt`

**Interfaces:**
- Consumes: existing provider key properties on `SettingsProvider`.
- Produces: `ConfigStore.contains(key: String): Boolean`, `SettingsProvider.hasKey(LlmProvider)`, and `SettingsProvider.hasKey(VoiceRecognitionProvider)` member functions whose desktop and Android overrides do not decrypt.

- [x] **Step 1: Add failing raw-presence tests**

Add these imports and tests to `SecretPrefsCodecTest.kt`:

```kotlin
import io.mockk.mockk
import ru.souz.llms.LlmProvider
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Test
fun `raw presence check does not migrate or decrypt a sensitive value`() =
    withStoredPreference(ConfigStore.TG_BOT_TOKEN) { key ->
        val legacy = "legacy-secret"
        prefs.put(key, legacy)

        assertTrue(ConfigStore.contains(key))
        assertEquals(legacy, prefs.get(key, null))

        ConfigStore.rm(key)
        assertFalse(ConfigStore.contains(key))
    }

@Test
fun `desktop provider presence accepts malformed encrypted payload without decrypting it`() =
    withStoredPreference(SettingsProviderImpl.AI_TUNNEL_KEY) { key ->
        prefs.put(key, "enc:v1:malformed")
        val provider = SettingsProviderImpl(ConfigStore, mockk(relaxed = true))

        assertTrue(provider.hasKey(LlmProvider.AI_TUNNEL))
        assertEquals("enc:v1:malformed", prefs.get(key, null))
    }
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :sharedLogic:jvmTest --tests ru.souz.db.SecretPrefsCodecTest
```

Expected: compilation fails because `ConfigStore.contains` does not exist; after only that symbol is introduced, the provider test still fails because the existing extension attempts decryption.

- [x] **Step 3: Implement raw presence and provider member functions**

Add to `ConfigStore`:

```kotlin
fun contains(key: String): Boolean = prefs.get(key, null) != null
```

Move the two `hasKey` functions into `SettingsProvider` as default members, retaining their current property-based bodies for mocks and non-production implementations. Override them in `SettingsProviderImpl` with:

```kotlin
override fun hasKey(provider: LlmProvider): Boolean = when (provider) {
    LlmProvider.GIGA -> hasConfiguredValue(GIGA_CHAT_KEY, "GIGA_KEY")
    LlmProvider.QWEN -> hasConfiguredValue(QWEN_CHAT_KEY, "QWEN_KEY")
    LlmProvider.AI_TUNNEL -> hasConfiguredValue(AI_TUNNEL_KEY, "AITUNNEL_KEY")
    LlmProvider.ANTHROPIC -> hasConfiguredValue(ANTHROPIC_KEY, "ANTHROPIC_API_KEY")
    LlmProvider.OPENAI -> hasConfiguredValue(OPENAI_KEY, "OPENAI_API_KEY")
    LlmProvider.LOCAL -> true
    LlmProvider.CODEX -> hasConfiguredValue(CODEX_ACCESS_TOKEN, CODEX_ACCESS_TOKEN)
}

override fun hasKey(provider: VoiceRecognitionProvider): Boolean = when (provider) {
    VoiceRecognitionProvider.SALUTE_SPEECH -> hasConfiguredValue(SALUTE_SPEECH_KEY, "VOICE_KEY")
    VoiceRecognitionProvider.AI_TUNNEL -> hasConfiguredValue(AI_TUNNEL_KEY, "AITUNNEL_KEY")
    VoiceRecognitionProvider.OPENAI -> hasConfiguredValue(OPENAI_KEY, "OPENAI_API_KEY")
    VoiceRecognitionProvider.LOCAL_MACOS -> true
}

private fun hasConfiguredValue(configKey: String, envKey: String, sysPropKey: String = envKey): Boolean =
    configStore.contains(configKey) ||
        !System.getenv(envKey).isNullOrBlank() ||
        !System.getProperty(sysPropKey).isNullOrBlank()
```

Override the same members in `AndroidSettingsProvider` using `prefs.contains(key)` and `true` for local providers. Do not invoke `secretString` from either override.

- [x] **Step 4: Run focused and platform compile verification**

Run:

```bash
./gradlew :sharedLogic:jvmTest --tests ru.souz.db.SecretPrefsCodecTest
./gradlew :sharedLogic:compileAndroidMain
```

Expected: both commands pass; the malformed payload is reported as configured without a decrypt warning.

- [x] **Step 5: Commit Task 1**

```bash
git add sharedLogic/src/jvmMain/kotlin/ru/souz/db/ConfigStore.kt \
  sharedLogic/src/commonJvmMain/kotlin/ru/souz/db/SettingsProvider.kt \
  sharedLogic/src/jvmMain/kotlin/ru/souz/db/SettingsProviderImpl.kt \
  sharedLogic/src/androidMain/kotlin/ru/souz/android/settings/AndroidSettingsProvider.kt \
  sharedLogic/src/test/kotlin/ru/souz/db/SecretPrefsCodecTest.kt
git commit -m "perf: check provider key presence without decrypting"
```

### Task 2: Provider-level model availability snapshots

**Files:**
- Modify: `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/ModelAvailability.kt`
- Test: `sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/ModelAvailabilityTest.kt`

**Interfaces:**
- Consumes: non-decrypting `SettingsProvider.hasKey` member functions from Task 1.
- Produces: model lists that query each distinct provider at most once per calculation.

- [x] **Step 1: Add a failing provider-deduplication test**

Add to `ModelAvailabilityTest.kt`:

```kotlin
import io.mockk.verify

@Test
fun `available llm models query configured state once per provider`() {
    val settingsProvider = mockk<SettingsProvider>(relaxed = true)
    every { settingsProvider.regionProfile } returns REGION_EN
    every { settingsProvider.hasKey(any<LlmProvider>()) } answers {
        firstArg<LlmProvider>() == LlmProvider.QWEN
    }

    val models = settingsProvider.availableLlmModels(LlmBuildProfile(settingsProvider))

    assertEquals(
        LLMModel.entries.filter { it.provider == LlmProvider.QWEN },
        models,
    )
    verify(exactly = 1) { settingsProvider.hasKey(LlmProvider.QWEN) }
    verify(exactly = 0) { settingsProvider.qwenChatKey }
}
```

- [x] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.ModelAvailabilityTest
```

Expected: verification fails because Qwen has multiple model entries and `hasKey(QWEN)` is called repeatedly.

- [x] **Step 3: Filter through distinct provider sets**

Implement `availableLlmModels` with one provider snapshot:

```kotlin
fun SettingsProvider.availableLlmModels(llmBuildProfile: LlmBuildProfile): List<LLMModel> {
    val models = llmBuildProfile.availableModels
    val configuredProviders = models
        .mapTo(linkedSetOf()) { it.provider }
        .filterTo(linkedSetOf(), this::hasKey)
    return models.filter { it.provider in configuredProviders }
}
```

Apply the same distinct-provider pattern to embeddings and voice model lists. Preserve local-model and local-macOS special cases.

- [x] **Step 4: Run model availability and Main ViewModel tests**

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.ModelAvailabilityTest \
  --tests ru.souz.ui.main.MainViewModelTest
```

Expected: all selected tests pass without reading provider key properties during availability filtering.

- [x] **Step 5: Commit Task 2**

```bash
git add sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/ModelAvailability.kt \
  sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/ModelAvailabilityTest.kt
git commit -m "perf: snapshot configured model providers"
```

### Task 3: On-demand secret use case and field state

**Files:**
- Create: `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/ApiKeySettingsUseCase.kt`
- Modify: `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/SettingsDTO.kt`
- Create: `sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/ApiKeySettingsUseCaseTest.kt`

**Interfaces:**
- Consumes: `SettingsProvider.hasKey`, provider key getters/setters, `ApiKeyField`, and an injected `CoroutineDispatcher`.
- Produces: `ApiKeyFieldState`, `ApiKeySettingsUseCase.initialState`, `reveal`, and `persist`.

- [x] **Step 1: Write failing use-case tests**

Create `ApiKeySettingsUseCaseTest.kt` with tests that define the desired API:

```kotlin
@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.settings

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.ui.common.ApiKeyField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiKeySettingsUseCaseTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `configured key starts hidden without reading its value`() {
        val provider = mockk<SettingsProvider>(relaxed = true)
        every { provider.hasKey(LlmProvider.AI_TUNNEL) } returns true

        val state = ApiKeySettingsUseCase(provider, dispatcher)
            .initialState(ApiKeyField.AI_TUNNEL)

        assertIs<ApiKeyFieldState.StoredHidden>(state)
        verify(exactly = 0) { provider.aiTunnelKey }
    }

    @Test
    fun `reveal reads only the selected key`() = runTest(dispatcher) {
        val provider = mockk<SettingsProvider>(relaxed = true)
        every { provider.aiTunnelKey } returns "ait-secret"

        val result = ApiKeySettingsUseCase(provider, dispatcher)
            .reveal(ApiKeyField.AI_TUNNEL)

        assertEquals("ait-secret", result.getOrThrow())
        verify(exactly = 1) { provider.aiTunnelKey }
        verify(exactly = 0) { provider.gigaChatKey }
        verify(exactly = 0) { provider.qwenChatKey }
    }

    @Test
    fun `persisting blank input removes selected key`() = runTest(dispatcher) {
        val provider = mockk<SettingsProvider>(relaxed = true)
        every { provider.aiTunnelKey = any() } just runs

        ApiKeySettingsUseCase(provider, dispatcher)
            .persist(ApiKeyField.AI_TUNNEL, "")

        verify(exactly = 1) { provider.aiTunnelKey = null }
    }
}
```

- [x] **Step 2: Run the new test and verify RED**

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.ApiKeySettingsUseCaseTest
```

Expected: compilation fails because `ApiKeyFieldState` and `ApiKeySettingsUseCase` do not exist.

- [x] **Step 3: Add the state machine and focused use case**

Add to `SettingsDTO.kt`:

```kotlin
sealed interface ApiKeyFieldState {
    data object StoredHidden : ApiKeyFieldState
    data object Revealing : ApiKeyFieldState
    data class Editable(val value: String, val revealed: Boolean) : ApiKeyFieldState
    data object RevealFailed : ApiKeyFieldState
}
```

Create `ApiKeySettingsUseCase` with exact provider mappings. `initialState` uses only `hasKey`; `reveal` and `persist` wrap the one selected getter/setter in `withContext(dispatcher)`. `persist` maps blank input to `null` and non-blank input to the entered string. `ApiKeyField.CODEX` returns an unsupported-operation failure because Codex remains OAuth-controlled.

- [x] **Step 4: Run use-case and Settings tests**

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.ApiKeySettingsUseCaseTest \
  --tests ru.souz.ui.settings.SettingsViewModelTest
```

Expected: the new tests pass and existing Settings behavior remains green before ViewModel migration.

- [x] **Step 5: Commit Task 3**

```bash
git add sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/ApiKeySettingsUseCase.kt \
  sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/SettingsDTO.kt \
  sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/ApiKeySettingsUseCaseTest.kt
git commit -m "feat: add on-demand provider key access"
```

### Task 4: Settings ViewModel secret lifecycle

**Files:**
- Modify: `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/SettingsDTO.kt`
- Modify: `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/SettingsViewModel.kt`
- Modify: `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/common/usecases/ApiKeyAvailabilityUseCase.kt`
- Test: `sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `ApiKeySettingsUseCase` from Task 3.
- Produces: `SettingsState.apiKeyFields`, `SettingsEvent.ToggleApiKeyVisibility`, non-eager refresh, and close-time concealment.

- [x] **Step 1: Add failing ViewModel behavior tests**

Extend `SettingsViewModelTest` with a fixture that injects the test dispatcher into `SettingsViewModel(di, secretDispatcher = dispatcher)`. Add focused tests with these assertions:

```kotlin
assertIs<ApiKeyFieldState.StoredHidden>(state.apiKeyFields.getValue(ApiKeyField.AI_TUNNEL))
verify(exactly = 0) { settingsProvider.aiTunnelKey }

viewModel.handleEvent(SettingsEvent.ToggleApiKeyVisibility(ApiKeyField.AI_TUNNEL))
advanceUntilIdle()
assertEquals(
    ApiKeyFieldState.Editable("ait-secret", revealed = true),
    viewModel.uiState.value.apiKeyFields.getValue(ApiKeyField.AI_TUNNEL),
)
verify(exactly = 1) { settingsProvider.aiTunnelKey }
verify(exactly = 0) { settingsProvider.anthropicKey }

viewModel.handleEvent(SettingsEvent.GoToMain)
advanceUntilIdle()
assertIs<ApiKeyFieldState.StoredHidden>(
    viewModel.uiState.value.apiKeyFields.getValue(ApiKeyField.AI_TUNNEL),
)
```

Add a failure test where `aiTunnelKey` throws; expect `RevealFailed`, a `ShowSnackbar` effect, and no setter call. Add an editing test that reveals, changes, advances 400 ms, and verifies the selected setter receives the new value while the field remains `Editable`.

- [x] **Step 2: Run the focused ViewModel tests and verify RED**

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.SettingsViewModelTest
```

Expected: compilation fails because `apiKeyFields`, `ToggleApiKeyVisibility`, and the dispatcher constructor parameter do not exist.

- [x] **Step 3: Replace eager key strings with state-map coordination**

Change `SettingsState` to contain:

```kotlin
val apiKeyFields: Map<ApiKeyField, ApiKeyFieldState> = emptyMap(),
val isClosing: Boolean = false,
```

Remove the six Settings-only key string properties. Add:

```kotlin
data class ToggleApiKeyVisibility(val field: ApiKeyField) : SettingsEvent
```

Construct `ApiKeySettingsUseCase(keysProvider, secretDispatcher)` in `SettingsViewModel`. During `refreshFromProvider`, initialize each available non-Codex field using `initialState` unless that field is currently editable or revealing. Replace `codexAccessToken` reads with `keysProvider.hasKey(LlmProvider.CODEX)`.

Handle visibility as follows:

```kotlin
StoredHidden, RevealFailed -> Revealing -> reveal -> Editable(value, true)
Editable(value, false) -> Editable(value, true)
Editable(value, true) -> flush selected draft -> StoredHidden or Editable("", false)
Revealing -> no-op
```

On reveal failure, set `RevealFailed`, log the exception without values, and emit `ShowSnackbar`. Change deferred-key state updates to write `Editable(value, current.revealed)` into the map. Persist through `ApiKeySettingsUseCase` and refresh only provider-dependent lists/counts, preserving the active field.

On `GoToMain`, mask all configured fields first, set `isClosing` while pending saves flush, clear draft maps, then emit `CloseScreen`. Count configured fields from `StoredHidden`, `Revealing`, `RevealFailed`, and non-blank `Editable` states plus Codex connection state.

- [x] **Step 4: Run Settings and setup regression tests**

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.SettingsViewModelTest \
  --tests ru.souz.ui.setup.SetupViewModelTest \
  --tests ru.souz.ui.settings.ModelAvailabilityTest
```

Expected: all selected tests pass; Settings refresh tests verify zero provider-secret getter calls.

- [x] **Step 5: Commit Task 4**

```bash
git add sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/SettingsDTO.kt \
  sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/SettingsViewModel.kt \
  sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/common/usecases/ApiKeyAvailabilityUseCase.kt \
  sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/SettingsViewModelTest.kt
git commit -m "feat: reveal settings keys only on demand"
```

### Task 5: Shared visibility UI and navigation lifecycle

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/ru/souz/ui/components/LabeledTextField.kt`
- Modify: `sharedUI/src/commonMain/kotlin/ru/souz/ui/sharedsettings/SharedSettingsUi.kt`
- Create: `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/ApiKeyFieldPresentation.kt`
- Modify: `sharedUI/src/jvmMain/kotlin/ru/souz/ui/settings/SettingsContent.kt`
- Modify: `sharedUI/src/jvmMain/kotlin/ru/souz/ui/settings/SettingsScreen.kt`
- Modify: `sharedUI/src/androidMain/kotlin/ru/souz/ui/android/SouzAndroidSharedUiApp.kt`
- Create: `sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/ApiKeyFieldPresentationTest.kt`

**Interfaces:**
- Consumes: `SettingsState.apiKeyFields` and `ToggleApiKeyVisibility` from Task 4.
- Produces: one eye control per key field, fixed mask/read-only modes, and close paths coordinated by the ViewModel.

- [x] **Step 1: Write failing presentation-mapping tests**

Create the non-composable mapper in `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/ApiKeyFieldPresentation.kt` and define its expected output in `ApiKeyFieldPresentationTest.kt`:

```kotlin
@Test
fun `stored hidden field uses fixed mask and cannot edit`() {
    val ui = ApiKeyFieldState.StoredHidden.toSharedField("AI_TUNNEL", "AI Tunnel")

    assertEquals(HIDDEN_API_KEY_MASK, ui.value)
    assertEquals(SharedApiKeyFieldMode.STORED_HIDDEN, ui.mode)
}

@Test
fun `revealed field exposes its real editable value`() {
    val ui = ApiKeyFieldState.Editable("ait-secret", revealed = true)
        .toSharedField("AI_TUNNEL", "AI Tunnel")

    assertEquals("ait-secret", ui.value)
    assertEquals(SharedApiKeyFieldMode.EDITABLE_REVEALED, ui.mode)
}
```

- [x] **Step 2: Run presentation tests and verify RED**

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.ApiKeyFieldPresentationTest
```

Expected: compilation fails because the mode, mask, and mapper do not exist.

- [x] **Step 3: Implement presentation modes and one visibility event**

Add:

```kotlin
enum class SharedApiKeyFieldMode {
    STORED_HIDDEN,
    REVEALING,
    EDITABLE_HIDDEN,
    EDITABLE_REVEALED,
    REVEAL_FAILED,
}
```

Extend `SharedApiKeyFieldUi` with `mode`, and add `SharedSettingsEvent.ApiKeyVisibilityToggled(id)`. Use a fixed `HIDDEN_API_KEY_MASK = "••••••••••••"` only in the mapper.

Extend `LabeledTextField` with `readOnly: Boolean = false` and `trailingContent: (@Composable () -> Unit)? = null`. Pass `readOnly` to `BasicTextField`; reserve end padding/space for trailing content.

In `SharedKeysSettingsContent`, render:

- a progress indicator for `REVEALING`;
- `Visibility` for stored/hidden/failed modes;
- `VisibilityOff` for revealed mode;
- password transformation only for editable-hidden mode;
- `onValueChange` only for editable modes.

Desktop and Android adapters map visibility events to `SettingsEvent.ToggleApiKeyVisibility`. Android's `AndroidKeyField` consumes the same state modes and eye behavior.

- [x] **Step 4: Remove duplicate refresh and route closes through the ViewModel**

In desktop and Android Settings routes, remove the unconditional `LaunchedEffect(Unit) { RefreshFromProvider }` because `SettingsViewModel.init` already refreshes. Remove `effects.debounce(2000)` on desktop. Replace direct Main-level `onClose` calls with `viewModel.send(SettingsEvent.GoToMain)`; retain BackToSettings behavior for nested sub-screens. Display a small progress indicator when `state.isClosing` without adding controls.

- [x] **Step 5: Run UI tests and platform compilation**

```bash
./gradlew :sharedUI:jvmTest --tests ru.souz.ui.settings.ApiKeyFieldPresentationTest \
  --tests ru.souz.ui.settings.SettingsViewModelTest
./gradlew :sharedUI:compileKotlinJvm :sharedLogic:compileAndroidMain :androidApp:assembleDebug
```

Expected: all commands pass; desktop and Android compile with the shared field-state contract.

- [x] **Step 6: Commit Task 5**

```bash
git add sharedUI/src/commonMain/kotlin/ru/souz/ui/components/LabeledTextField.kt \
  sharedUI/src/commonMain/kotlin/ru/souz/ui/sharedsettings/SharedSettingsUi.kt \
  sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings/ApiKeyFieldPresentation.kt \
  sharedUI/src/jvmMain/kotlin/ru/souz/ui/settings/SettingsContent.kt \
  sharedUI/src/jvmMain/kotlin/ru/souz/ui/settings/SettingsScreen.kt \
  sharedUI/src/androidMain/kotlin/ru/souz/ui/android/SouzAndroidSharedUiApp.kt \
  sharedUI/src/jvmTest/kotlin/ru/souz/ui/settings/ApiKeyFieldPresentationTest.kt
git commit -m "feat: add on-demand key visibility controls"
```

### Task 6: Full verification, profiling, and design-spec cleanup

**Files:**
- Delete: `docs/superpowers/specs/2026-07-13-desktop-gui-secret-responsiveness-design.md`
- Keep: `docs/superpowers/plans/2026-07-14-desktop-gui-secret-responsiveness.md`

**Interfaces:**
- Consumes: completed Tasks 1–5.
- Produces: verified branch with no temporary design spec and evidence that navigation no longer decrypts on AWT.

- [x] **Step 1: Run the complete relevant automated suite**

```bash
./gradlew :sharedLogic:jvmTest :sharedUI:jvmTest :desktopApp:test
./gradlew :sharedUI:compileKotlinJvm :sharedLogic:compileAndroidMain :androidApp:assembleDebug
```

Expected: all tasks finish successfully with zero failing tests.

- [x] **Step 2: Run desktop transition verification**

Launch the desktop application, repeat Main → Settings → Main, Main → Memory → Main, and Tools → Settings five times, and record a 30–45 second JFR profile. Without pressing reveal or editing keys, query the profile for `AesGcmSecretCodec`, `PBKDF2KeyImpl`, `SettingsViewModel.refreshFromProvider`, and `MainViewModel.refreshSettings`.

Expected: zero AES/PBKDF2 samples under `AWT-EventQueue-0`; no continuous application-settings CPU interval above 50 ms; visible transition response within 100 ms.

- [x] **Step 3: Remove the temporary design spec**

Use `apply_patch` to delete:

```text
docs/superpowers/specs/2026-07-13-desktop-gui-secret-responsiveness-design.md
```

Confirm `git status --short` reports the intended deletion and no unrelated changes.

- [x] **Step 4: Commit cleanup and final evidence**

```bash
git add docs/superpowers/specs/2026-07-13-desktop-gui-secret-responsiveness-design.md \
  docs/superpowers/plans/2026-07-14-desktop-gui-secret-responsiveness.md
git commit -m "docs: finalize gui responsiveness implementation"
```

- [x] **Step 5: Review the final diff**

```bash
git status --short
git diff main...HEAD --check
git diff main...HEAD --stat
```

Expected: clean working tree, no whitespace errors, the design spec absent at branch tip, and only the planned settings/performance/test/documentation files changed.

## Release validation note

The first packaged-app review exposed a sticky full-screen closing indicator after returning to Settings. A regression test now verifies that `GoToMain` always clears `isClosing`, including exceptional save paths, and the global indicator was removed from the desktop Settings presentation.
