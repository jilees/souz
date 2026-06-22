package ru.souz.ui.memory

import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.memory.MemoryMaintenanceController
import ru.souz.memory.MemoryMaintenanceMode
import ru.souz.memory.NoopMemoryMaintenanceController
import ru.souz.memory.MemoryService
import ru.souz.ui.BaseViewModel

class MemoryViewModel(
    override val di: DI,
) : BaseViewModel<MemoryUiState, MemoryAction, MemoryEffect>(), DIAware {

    private val memoryService: MemoryService by di.instance()
    private val maintenanceController: MemoryMaintenanceController =
        di.direct.instanceOrNull<MemoryMaintenanceController>() ?: NoopMemoryMaintenanceController

    override fun initialState(): MemoryUiState = MemoryUiState()

    fun onAction(action: MemoryAction) = send(action)

    override suspend fun handleEvent(event: MemoryAction) {
        when (event) {
            MemoryAction.Load -> loadFacts()
            MemoryAction.RefreshDreamerStatus -> refreshMaintenanceStatus()
            is MemoryAction.ChangeFilters -> changeFilters(event.filters)
            MemoryAction.OpenCreateDialog -> setState { copy(editor = newMemoryEditorState(), error = null) }
            is MemoryAction.OpenEditDialog -> openEditDialog(event.factId)
            is MemoryAction.SaveFact -> saveFact(event.input)
            is MemoryAction.OpenDetails -> loadDetails(event.factId)
            MemoryAction.CloseDetails -> closeDetails()
            is MemoryAction.SetPinned -> setPinned(event.factId, event.pinned)
            is MemoryAction.AskRetire -> askRetire(event.factId)
            is MemoryAction.AskDelete -> askDelete(event.factId)
            MemoryAction.ConfirmAction -> confirmAction()
            MemoryAction.CancelConfirmAction -> setState { copy(confirm = null) }
            MemoryAction.CloseDialog -> setState { copy(editor = null) }
            MemoryAction.ClearError -> setState { copy(error = null) }
            is MemoryAction.SetDreamerEnabled -> setDreamerEnabled(event.enabled)
            is MemoryAction.SelectDreamerMode -> selectDreamerMode(event.mode)
            is MemoryAction.SetDailyTokenLimit -> setMaintenanceInput { copy(dailyCloudTokenLimitInput = event.value) }
            is MemoryAction.SetDailyCallLimit -> setMaintenanceInput { copy(maxCloudCallsPerDayInput = event.value) }
            is MemoryAction.SetMaxTokensPerRun -> setMaintenanceInput { copy(maxTokensPerRunInput = event.value) }
            is MemoryAction.SetMaxClustersPerRun -> setMaintenanceInput { copy(maxClustersPerRunInput = event.value) }
            is MemoryAction.SetRunWhenIdle -> saveMaintenance(currentState.maintenance.copy(runWhenIdle = event.enabled))
            MemoryAction.RunDreamerNow -> runDreamerNow()
        }
    }

    override suspend fun handleSideEffect(effect: MemoryEffect) = Unit

    private suspend fun loadFacts() {
        setState { copy(isLoading = true, error = null) }
        runCatching {
            memoryService.listFacts(currentState.filters.toDomainFilter())
        }.onSuccess { facts ->
            setState {
                copy(
                    facts = facts.sortedForUi(),
                    isLoading = false,
                )
            }
        }.onFailure { error ->
            fail(error, "Failed to load memory")
            setState { copy(isLoading = false) }
        }
        refreshMaintenanceStatus()
    }

    private suspend fun refreshMaintenanceStatus() {
        runCatching {
            maintenanceController.status()
        }.onSuccess { status ->
            setState { copy(maintenance = status.toUiState()) }
        }.onFailure { error ->
            fail(error, "Failed to load Dreamer status")
        }
    }

    private suspend fun changeFilters(filters: MemoryFiltersUi) {
        setState { copy(filters = filters) }
        loadFacts()
    }

    private suspend fun openEditDialog(factId: String) {
        setState { copy(error = null) }
        runCatching {
            memoryService.getFactDetails(factId)?.toEditorState()
                ?: error("Memory fact not found: $factId")
        }.onSuccess { editor ->
            setState { copy(editor = editor) }
        }.onFailure { error ->
            fail(error, "Failed to open memory fact")
        }
    }

    private suspend fun saveFact(input: MemoryEditorInput) {
        validate(input)?.let { message ->
            fail(IllegalArgumentException(message), message)
            return
        }

        setState { copy(isSaving = true, error = null) }
        runCatching {
            if (input.factId == null) {
                memoryService.createManualFact(input.toCreateInput())
            } else {
                memoryService.updateFact(input.factId, input.toPatch())
            }
        }.onSuccess { fact ->
            setState { copy(isSaving = false, editor = null) }
            loadFacts()
            refreshDetailsIfSelected(fact.id)
        }.onFailure { error ->
            fail(error, "Failed to save memory fact")
            setState { copy(isSaving = false) }
        }
    }

    private suspend fun loadDetails(factId: String) {
        setState {
            copy(
                detailsFactId = factId,
                isDetailsLoading = true,
                selectedFact = null,
                error = null,
            )
        }
        runCatching {
            memoryService.getFactDetails(factId)
                ?: error("Memory fact not found: $factId")
        }.onSuccess { details ->
            setState {
                copy(
                    selectedFact = details,
                    isDetailsLoading = false,
                )
            }
        }.onFailure { error ->
            fail(error, "Failed to load memory details")
            setState {
                copy(
                    detailsFactId = null,
                    selectedFact = null,
                    isDetailsLoading = false,
                )
            }
        }
    }

    private suspend fun closeDetails() {
        setState {
            copy(
                detailsFactId = null,
                selectedFact = null,
                isDetailsLoading = false,
            )
        }
    }

    private suspend fun setPinned(
        factId: String,
        pinned: Boolean,
    ) {
        runCatching {
            memoryService.updateFact(factId, patch = ru.souz.memory.MemoryFactPatch(pinned = pinned))
        }.onSuccess {
            loadFacts()
            refreshDetailsIfSelected(factId)
        }.onFailure { error ->
            fail(error, "Failed to update memory fact")
        }
    }

    private suspend fun askRetire(factId: String) {
        setState {
            copy(confirm = PendingMemoryConfirm(PendingMemoryConfirm.Kind.Retire, factId))
        }
    }

    private suspend fun askDelete(factId: String) {
        setState {
            copy(confirm = PendingMemoryConfirm(PendingMemoryConfirm.Kind.Delete, factId))
        }
    }

    private suspend fun confirmAction() {
        val action = currentState.confirm ?: return
        setState { copy(confirm = null, error = null) }

        runCatching {
            when (action.kind) {
                PendingMemoryConfirm.Kind.Delete -> memoryService.deleteFact(action.factId)
                PendingMemoryConfirm.Kind.Retire -> memoryService.retireFact(action.factId)
            }
        }.onSuccess {
            if (currentState.detailsFactId == action.factId) {
                closeDetails()
            }
            loadFacts()
        }.onFailure { error ->
            fail(
                error,
                when (action.kind) {
                    PendingMemoryConfirm.Kind.Delete -> "Failed to delete memory fact"
                    PendingMemoryConfirm.Kind.Retire -> "Failed to retire memory fact"
                },
            )
        }
    }

    private suspend fun refreshDetailsIfSelected(factId: String) {
        if (currentState.detailsFactId == factId) {
            loadDetails(factId)
        }
    }

    private suspend fun fail(
        error: Throwable,
        fallback: String,
    ) {
        val message = error.message?.takeIf(String::isNotBlank) ?: fallback
        setState { copy(error = message) }
        send(MemoryEffect.ShowError(message))
    }

    private fun validate(input: MemoryEditorInput): String? = when {
        input.title.isBlank() -> "Title is required"
        input.body.isBlank() -> "Body is required"
        input.scopeType.isBlank() -> "Scope type is required"
        input.scopeId.isBlank() -> "Scope id is required"
        else -> null
    }

    private suspend fun setDreamerEnabled(enabled: Boolean) {
        val current = currentState.maintenance
        val nextMode = if (enabled) {
            current.lastEnabledMode.takeIf { it != MemoryMaintenanceMode.OFF } ?: MemoryMaintenanceMode.LOCAL_ONLY
        } else {
            MemoryMaintenanceMode.OFF
        }
        saveMaintenance(current.copy(mode = nextMode))
    }

    private suspend fun selectDreamerMode(mode: MemoryMaintenanceMode) {
        val normalized = if (mode == MemoryMaintenanceMode.OFF) MemoryMaintenanceMode.OFF else mode
        saveMaintenance(
            currentState.maintenance.copy(
                mode = normalized,
                lastEnabledMode = normalized.takeIf { it != MemoryMaintenanceMode.OFF }
                    ?: currentState.maintenance.lastEnabledMode,
            )
        )
    }

    private suspend fun setMaintenanceInput(update: MemoryMaintenanceUiState.() -> MemoryMaintenanceUiState) {
        setState { copy(maintenance = maintenance.update().copy(fieldError = null)) }
    }

    private suspend fun saveMaintenance(next: MemoryMaintenanceUiState) {
        val preferences = next.toPreferences()
        if (preferences == null) {
            setState { copy(maintenance = next.copy(fieldError = "Invalid Dreamer limits")) }
            return
        }
        runCatching {
            maintenanceController.savePreferences(preferences)
        }.onSuccess { status ->
            setState { copy(maintenance = status.toUiState()) }
        }.onFailure { error ->
            fail(error, "Failed to save Dreamer settings")
        }
    }

    private suspend fun runDreamerNow() {
        if (currentState.maintenance.mode == MemoryMaintenanceMode.OFF) return
        setState { copy(maintenance = maintenance.copy(isRunningNow = true)) }
        runCatching {
            maintenanceController.runNow()
        }.onSuccess { status ->
            setState { copy(maintenance = status.toUiState(isRunningNow = false)) }
        }.onFailure { error ->
            fail(error, "Failed to run Dreamer")
            setState { copy(maintenance = maintenance.copy(isRunningNow = false)) }
        }
    }
}
