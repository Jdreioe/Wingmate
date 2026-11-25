package io.github.jdreioe.wingmate.ui

import android.content.res.Resources
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.GridsetExportResult
import io.github.jdreioe.wingmate.application.GridsetExporter
import io.github.jdreioe.wingmate.application.ObzExportResult
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.fromScreenButton
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.domain.obf.ResolvedBoardSettings
import io.github.jdreioe.wingmate.domain.obf.applyBoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.nGramPredictionInsertion
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import io.github.jdreioe.wingmate.domain.obf.printableScreen
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakSelectionImmediately
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal suspend fun exportBoardSetAsObz(
    graph: BoardSetGraph?,
    useCase: BoardSetUseCase,
    shareService: ShareService?,
    exportedTemplate: String,
    exportCancelledMessage: String,
    exportErrorMessage: String,
): String {
    if (graph == null) return exportErrorMessage
    return try {
        when (val export = useCase.exportBoardSetAsObzResult(graph.boardSet.id)) {
            is ObzExportResult.Success -> {
                val fileName = "${graph.boardSet.name}.obz"
                if (shareService != null) {
                    if (shareService.shareFile(fileName, export.bytes)) {
                        exportedTemplate.replace("%NAME%", fileName)
                    } else {
                        exportCancelledMessage
                    }
                } else {
                    // Without a share sheet there is nowhere to deliver the file.
                    exportErrorMessage
                }
            }
            is ObzExportResult.Failure -> exportErrorMessage
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        exportErrorMessage
    }
}

internal suspend fun exportBoardSetAsGridset(
    graph: BoardSetGraph?,
    useCase: BoardSetUseCase,
    settings: Settings,
    mediaUrlLoader: ObfMediaUrlLoader?,
    shareService: ShareService?,
    exportedTemplate: String,
    exportCancelledMessage: String,
    exportErrorMessage: String,
): String {
    if (graph == null) return exportErrorMessage
    return try {
        when (
            val export = useCase.exportBoardSetAsGridsetResult(
                boardSetId = graph.boardSet.id,
                appSettings = settings,
                loadUrl = mediaUrlLoader?.let { loader -> { url -> loader.load(url) } },
            )
        ) {
            is GridsetExportResult.Success -> {
                val fileName = GridsetExporter.sanitizeExportFileName(graph.boardSet.name, "gridset")
                if (shareService != null) {
                    if (shareService.shareFile(fileName, export.bytes)) {
                        exportedTemplate.replace("%NAME%", fileName)
                    } else {
                        exportCancelledMessage
                    }
                } else {
                    exportErrorMessage
                }
            }
            is GridsetExportResult.Failure -> exportErrorMessage
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        exportErrorMessage
    }
}

/** Shares the whole Screen as a printable PDF, the fallback for when no device works (#227). */
internal suspend fun printBoardSetAsPdf(
    graph: BoardSetGraph?,
    settings: Settings,
    fileStorage: FileStorage,
    mediaUrlLoader: ObfMediaUrlLoader?,
    shareService: ShareService?,
    resources: Resources,
    exportedTemplate: String,
    exportCancelledMessage: String,
    exportErrorMessage: String,
): String {
    if (graph == null || shareService == null) return exportErrorMessage
    return try {
        val pdf = renderBoardPdf(
            pages = printableScreen(graph, settings.primaryLanguage),
            loadImage = { image -> loadObfImageBytes(image, fileStorage, mediaUrlLoader) },
            opensLabel = { link -> resources.getString(R.string.board_workspace_print_opens, link.title, link.pageNumber) },
        )
        val fileName = "${graph.boardSet.name}.pdf"
        if (shareService.shareFile(fileName, pdf)) {
            exportedTemplate.replace("%NAME%", fileName)
        } else {
            exportCancelledMessage
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        exportErrorMessage
    }
}

internal fun handleWorkspaceButtonClick(
    button: ObfButton,
    activeGraph: BoardSetGraph,
    activeBoard: ObfBoard,
    workspace: BoardWorkspaceState,
    settings: Settings,
    resolvedBoardSettings: ResolvedBoardSettings,
    selectedVoice: Voice?,
    predictionButtonIds: List<String>,
    predictionsById: Map<String, String>,
    communicationSession: CommunicationSession,
    workspaceViewModel: BoardWorkspaceViewModel,
    fileStorage: FileStorage,
    soundPlayer: SoundPlayer,
    mediaUrlLoader: ObfMediaUrlLoader?,
    scope: CoroutineScope,
    unsupportedActionTemplate: String,
    onMarkButtonSelected: (String) -> Unit,
    onOpenNativeKeyboardDraft: (String) -> Unit,
) {
    // #118: debounce gating lives in ObfButtonItem, which only dispatches accepted activations.
    onMarkButtonSelected(button.id)
    val actions = parseObfButtonActions(button)
    if (actions.isNotEmpty()) {
        var navigateHome = false
        for (effect in actions) {
            when (effect) {
                is ObfButtonActionEffect.AppendText -> {
                    if (effect.text.isNotEmpty()) {
                        communicationSession.accept(
                            CommunicationAction.AppendPart(
                                part = MessagePart(
                                    displayText = effect.text,
                                    languageTag = button.locale,
                                    mathMode = button.mathMode,
                                ),
                                spellingMode = activeBoard.spellingMode,
                            )
                        )
                    }
                }
                is ObfButtonActionEffect.WrapSelection -> {
                    // Token sentences hold no selection, so wrap falls back
                    // to inserting prefix + fallback + suffix as one token.
                    val wrapped = effect.prefix + effect.fallback + effect.suffix
                    communicationSession.accept(
                        CommunicationAction.AppendPart(
                            part = MessagePart(
                                displayText = wrapped,
                                languageTag = button.locale,
                                mathMode = button.mathMode,
                            ),
                            spellingMode = activeBoard.spellingMode,
                        )
                    )
                }
                ObfButtonActionEffect.Backspace -> {
                    communicationSession.accept(
                        CommunicationAction.RemoveLastPart(activeBoard.spellingMode)
                    )
                }
                ObfButtonActionEffect.Clear -> {
                    communicationSession.accept(CommunicationAction.Clear)
                }
                ObfButtonActionEffect.Speak -> {
                    communicationSession.accept(
                        CommunicationAction.SpeakActive(
                            voice = selectedVoice,
                            cacheAudio = activeGraph.boardSet.cacheWholeSentences,
                        )
                    )
                }
                ObfButtonActionEffect.Home -> {
                    navigateHome = true
                }
                ObfButtonActionEffect.NativeKeyboard -> {
                    onOpenNativeKeyboardDraft(communicationSession.state.value.activeMessage.displayText)
                }
                ObfButtonActionEffect.Predictions -> {
                    val currentText = communicationSession.state.value.activeMessage.displayText
                    val insertion = predictionButtonIds
                        .indexOf(button.id)
                        .takeIf { it != -1 }
                        ?.let { predictionsById[button.id] }
                        ?.let { nGramPredictionInsertion(currentText, it) }
                    if (!insertion.isNullOrEmpty()) {
                        communicationSession.accept(
                            CommunicationAction.AppendPart(
                                part = MessagePart(
                                    displayText = insertion,
                                    languageTag = button.locale,
                                ),
                                spellingMode = activeBoard.spellingMode,
                            )
                        )
                    }
                }
                ObfButtonActionEffect.Pause -> communicationSession.accept(CommunicationAction.Pause)
                ObfButtonActionEffect.Resume -> communicationSession.accept(CommunicationAction.Resume)
                ObfButtonActionEffect.Stop -> communicationSession.accept(CommunicationAction.Stop)
                ObfButtonActionEffect.SwapHeldMessage -> communicationSession.accept(CommunicationAction.SwapHeldMessage)
                ObfButtonActionEffect.ToggleSecondaryLanguage -> {
                    workspaceViewModel.onAction(
                        BoardWorkspaceAction.StatusChanged(
                            unsupportedActionTemplate.replace("%ACTION%", button.action.orEmpty())
                        )
                    )
                }
                is ObfButtonActionEffect.Unsupported -> {
                    workspaceViewModel.onAction(
                        BoardWorkspaceAction.StatusChanged(
                            unsupportedActionTemplate.replace("%ACTION%", effect.action)
                        )
                    )
                }
            }
        }
        if (navigateHome) {
            workspaceViewModel.onAction(
                BoardWorkspaceAction.GoHome(activeGraph.boardSet.rootBoardId)
            )
        }
        return
    }

    val linkedBoard = activeGraph.resolveLinkedBoard(button.loadBoard)
    if (linkedBoard != null) {
        workspaceViewModel.onAction(BoardWorkspaceAction.OpenBoard(linkedBoard.id))
        return
    }

    val part = MessagePart.fromScreenButton(
        screenId = activeGraph.boardSet.id,
        board = activeBoard,
        button = button,
        primaryLanguage = settings.primaryLanguage,
    )
    if (part != null && shouldAddBoardSelection(resolvedBoardSettings.activationBehavior)) {
        communicationSession.accept(
            CommunicationAction.AppendPart(
                part = part,
                spellingMode = activeBoard.spellingMode,
            )
        )
    }
    val sound = button.soundId?.let { id ->
        activeBoard.sounds.firstOrNull { it.id == id }
    }
    if (shouldSpeakSelectionImmediately(
            resolvedBoardSettings.speechPolicy ?: settings.speechPolicy,
            resolvedBoardSettings.activationBehavior
        )
    ) {
        if (part != null) {
            communicationSession.accept(
                CommunicationAction.SpeakPart(
                    part = part,
                    voice = selectedVoice,
                )
            )
        } else if (sound != null) {
            scope.launch(Dispatchers.IO) {
                playButtonSound(
                    sound = sound,
                    fileStorage = fileStorage,
                    soundPlayer = soundPlayer,
                    urlLoader = mediaUrlLoader
                )
            }
        }
    }
    if (part != null || sound != null) {
        val returned = applyBoardReturnBehavior(
            behavior = resolvedBoardSettings.returnBehavior,
            currentBoardId = workspace.selectedBoardId,
            boardStack = workspace.boardStack,
            rootBoardId = activeGraph.boardSet.rootBoardId
        )
        workspaceViewModel.onAction(
            BoardWorkspaceAction.RestorePosition(
                selectedBoardId = returned.first,
                boardStack = returned.second,
            )
        )
    }
}
