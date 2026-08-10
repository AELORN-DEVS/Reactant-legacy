package dev.reactant.reactant.core.commands.component

import dev.reactant.reactant.core.commands.ReactantPermissions
import dev.reactant.reactant.core.component.container.ContainerManager
import dev.reactant.reactant.core.dependency.ProviderManager
import dev.reactant.reactant.core.dependency.injection.producer.ComponentProvider
import dev.reactant.reactant.core.dependency.injection.producer.Provider
import dev.reactant.reactant.extra.command.ReactantCommand
import dev.reactant.reactant.extra.file.FileIOUploadService
import dev.reactant.reactant.extra.parser.GsonJsonParserService
import dev.reactant.reactant.utils.PatternMatchingUtils
import dev.reactant.reactant.utils.formatting.MultiColumns
import okhttp3.MediaType.Companion.toMediaType
import picocli.CommandLine
import java.util.regex.Pattern
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.jvmName

@CommandLine.Command(
    name = "ls",
    aliases = ["list"],
    mixinStandardHelpOptions = true,
    description = ["Listing all Component"]
)
internal class ReactantComponentListSubCommand(
    val providerManager: ProviderManager,
    val containerManager: ContainerManager,
    val jsonParserService: GsonJsonParserService,
    val fileIOUploadService: FileIOUploadService
) : ReactantCommand(ReactantPermissions.ADMIN.DEV.OBJ.LIST.toString()) {

    @CommandLine.Option(
        names = ["-r", "--is-running"], paramLabel = "IS_RUNNING",
        description = ["Filtering Component by running state"]
    )
    var isRunning: Boolean? = null

    @CommandLine.Option(
        names = ["-p", "--pattern"], paramLabel = "REG_EXP",
        description = ["Filtering Component class canonical name by RegExp"]
    )
    var classNamePattern: Pattern? = null

    @CommandLine.Option(
        names = ["-o", "--short"],
        description = ["Display class short name instead of canonical name"]
    )
    var showShortName: Boolean = false

    @CommandLine.Option(
        names = ["-c", "--container"],
        description = ["Filtering the Component by container rawIdentifier, wildcard is available"]
    )
    var containerIdentifierWildcards: ArrayList<String> = arrayListOf()

    @CommandLine.Parameters(
        arity = "0..*", paramLabel = "CLASS_NAME",
        description = ["Filtering Component class canonical name, wildcard is available"]
    )
    var classNameWildcards: ArrayList<String> = arrayListOf()

    @CommandLine.Option(
        names = ["-e", "--export"],
        description = ["Export the file as json"]
    )
    var export: Boolean = false

    private val listTable = MultiColumns.create {
        column { align = MultiColumns.Alignment.Right }
        column { maxLength = 50; overflowCutFromRight = false; }
        column { maxLength = 40 }
        column { align = MultiColumns.Alignment.Center }
    }

    override fun execute() {
        requirePermission(ReactantPermissions.ADMIN.DEV.OBJ.LIST)

        providerManager.providers.union(providerManager.blacklistedProviders).mapNotNull { it as? ComponentProvider<*> }
            .asSequence()
            // State filter
            .filter { isRunning == null || it.isInitialized() == isRunning }
            // Class name pattern filter
            .filter {
                classNamePattern == null ||
                    classNamePattern!!.toRegex().matches(it.productType.jvmErasure.jvmName)
            }
            // Class name wildcards filter
            .filter { matchClassNameWildcards(it) }
            .filter { matchContainerIdentifierWildcards(it.container.identifier) }
            .toList()
            .map { ComponentRow(it, showShortName, providerManager.blacklistedProviders) }
            .let {
                if (export) {
                    stdout.out("Exporting components...")
                    jsonParserService.encode(it).flatMap {
                        fileIOUploadService.upload("component-list.json", it, "application/json".toMediaType())
                    }.subscribe { resp -> stdout.out("Exported and uploaded to ${resp.link}") }
                } else {
                    it.forEach { addToListTable(it) }
                    listTable.generate().forEach(stdout::out)
                }
            }
    }

    private fun matchClassNameWildcards(componentProvider: ComponentProvider<*>) =
        classNameWildcards.isEmpty() || classNameWildcards.any { wildcard ->
            PatternMatchingUtils.matchWildcard(wildcard, componentProvider.productType.jvmErasure.jvmName)
        }

    private fun matchContainerIdentifierWildcards(identifier: String) =
        containerIdentifierWildcards.isEmpty() || containerIdentifierWildcards.any { wildcard ->
            PatternMatchingUtils.matchWildcard(wildcard, identifier)
        }

    private fun addToListTable(componentRow: ComponentRow) {
        listTable.rows.add(
            listOf(
                componentRow.hashCode, componentRow.jvmName, componentRow.container, componentRow.state
            )
        )
    }
}

private data class ComponentRow(
    val hashCode: String,
    val jvmName: String,
    val container: String,
    val state: String
) {
    constructor(componentProvider: ComponentProvider<*>, showShortName: Boolean, blacklist: HashSet<Provider>) : this(
        componentProvider.hashCode().toString(36),
        componentProvider.productType.jvmErasure.let { if (showShortName) it.simpleName!! else it.jvmName },
        componentProvider.container.identifier,
        if (componentProvider.isInitialized()) "Running"
        else if (blacklist.contains(componentProvider)) "Blacklisted"
        else if (componentProvider.catchedThrowable != null) "Error"
        else if (!componentProvider.fulfilled) "Not Fulfilled" else ""
    )
}
