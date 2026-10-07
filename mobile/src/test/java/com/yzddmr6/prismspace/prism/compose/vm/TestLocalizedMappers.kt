package com.yzddmr6.prismspace.prism.compose.vm

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.engine.LaunchResult
import com.yzddmr6.prismspace.prism.compose.space.CreateSpaceResult
import com.yzddmr6.prismspace.prism.compose.space.DeleteSpaceResult
import com.yzddmr6.prismspace.prism.model.CapabilityState
import com.yzddmr6.prismspace.prism.model.PrismSettingsModeState
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Test-only resolver backed by the actual Simplified-Chinese Android resources. */
val testZhResolver: StringResolver by lazy {
    val namesById = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    val resourceDir = listOf(
        File("mobile/src/main/res/values-zh"),
        File("src/main/res/values-zh"),
    ).first(File::isDirectory)
    val templates = resourceDir.listFiles()
        .orEmpty()
        .filter { it.extension == "xml" }
        .flatMap { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            (0 until root.childNodes.length).mapNotNull { index ->
                val element = root.childNodes.item(index) as? Element ?: return@mapNotNull null
                if (element.tagName != "string") return@mapNotNull null
                element.getAttribute("name") to element.textContent
                    .replace("\\n", "\n")
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
            }
        }
        .toMap()
    val resolver: StringResolver = { id, args ->
        val name = requireNotNull(namesById[id]) { "Unknown string id: $id" }
        val template = requireNotNull(templates[name]) { "Missing values-zh string: $name" }
        if (args.isEmpty()) template else String.format(Locale.SIMPLIFIED_CHINESE, template, *args)
    }
    resolver
}

// Test-local overloads keep pure mapper tests concise without a production fallback catalog.
fun batchActionFeedback(action: BatchAction, succeeded: Int, failed: Int) =
    batchActionFeedback(action, succeeded, failed, testZhResolver)
internal fun uninstallQueueFeedback(summary: UninstallSummary, uninstallSkipped: Int = 0) =
    uninstallQueueFeedback(summary, uninstallSkipped, testZhResolver)
internal fun uninstallAbortFeedback(queue: UninstallQueueState, skipped: Int, guidance: String) =
    uninstallAbortFeedback(queue, skipped, guidance, testZhResolver)
internal fun batchCloneFeedback(counts: BatchCloneCounts) = batchCloneFeedback(counts, testZhResolver)
internal fun mapRows(inputs: List<SpaceAppInput>) = mapRows(inputs, testZhResolver)
internal fun mapSettingsUiModel(
    profileOwner: Boolean,
    shizukuAuthorized: Boolean,
    modeState: PrismSettingsModeState,
    capabilityState: CapabilityState,
    selectedMode: PrismMode = PrismMode.Normal,
) = mapSettingsUiModel(
    profileOwner,
    shizukuAuthorized,
    modeState,
    capabilityState,
    selectedMode,
    testZhResolver,
)
fun provisioningFeedback(result: CreateSpaceResult) = provisioningFeedback(result, testZhResolver)
fun provisioningFeedback(result: DeleteSpaceResult) = provisioningFeedback(result, testZhResolver)
fun specificRootSetupFailure(result: CreateSpaceResult) = specificRootSetupFailure(result, testZhResolver)
fun launchFeedback(result: LaunchResult, appLabel: String) = launchFeedback(result, appLabel, testZhResolver)
