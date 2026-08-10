package dev.reactant.reactant.repository.commands.repository

import dev.reactant.reactant.core.ReactantCore
import dev.reactant.reactant.core.ReactantCore.Companion.tmpDirPath
import dev.reactant.reactant.core.commands.ReactantPermissions
import dev.reactant.reactant.extensions.jarLocation
import dev.reactant.reactant.extra.command.ReactantCommand
import dev.reactant.reactant.repository.MavenRepositoryRetrieverService
import dev.reactant.reactant.repository.RepositoryService
import dev.reactant.reactant.utils.converter.StacktraceConverterUtils
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.ResponseBody
import okio.buffer
import okio.sink
import org.bukkit.Bukkit
import org.bukkit.plugin.InvalidDescriptionException
import org.bukkit.plugin.PluginDescriptionFile
import picocli.CommandLine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.util.*

@CommandLine.Command(
    name = "retrieve", aliases = ["ret"], mixinStandardHelpOptions = true,
    description = ["Retrieve a plugin from maven repositories"]
)
class RepositoryRetrieveSubCommand(
    private val repositoryService: RepositoryService,
    private val repositoryRetrieverService: MavenRepositoryRetrieverService
) : ReactantCommand(ReactantPermissions.REPOSITORY.RETRIEVE.toString()) {
    @CommandLine.Parameters(
        arity = "1..*", paramLabel = "IDENTIFIER",
        description = [
            "Gradle style rawIdentifier of the plugins artifact",
            "format: \"{groupId}:{artifactId}:{version}:{classifier(optional)}\""
        ]
    )
    lateinit var identifiers: ArrayList<String>

    @CommandLine.Option(
        names = ["-v", "--verbose"],
        description = ["Verbose mode, print more details"]
    )
    var verbose: Boolean = false

    @CommandLine.Option(
        names = ["-f", "--force"],
        description = [
            "Ignore main class not match problem when updating plugin",
            "Use only when you know what you are doing"
        ]
    )
    var ignoreMainClassNotMatch: Boolean = false

    override fun execute() {
        repositoryService.consoleOnlyValidate(sender)
        requirePermission(ReactantPermissions.REPOSITORY.RETRIEVE)
        stdout.out("Retrieving ${identifiers.size} plugin from repositories...")
        Observable.fromIterable(identifiers)
            .map(::ArtifactInfo)
            .flatMapSingle(this::download)
            .flatMapSingle(this::installPlugin)
            .subscribe(
                { stdout.out("Retrieve successfully as ${it.absolutePath}"); },
                {
                    stderr.out("Retrieve failed :${it.message ?: ""}")
                    if (verbose)
                        StacktraceConverterUtils.convertToString(it).split("\n").forEach(stdout::out)
                },
                { Bukkit.reload() }
            )
    }

    @Suppress("UNCHECKED_CAST")
    private fun download(artifact: ArtifactInfo): Single<File> {
        val randomId = UUID.randomUUID()
        val targetFile = File("$tmpDirPath/.$randomId${artifact.extension}")
        targetFile.parentFile.mkdirs()

        val requests = repositoryService.repositoriesMap.values.map(artifact::download)
        return downloadFirstValidRequest(requests, targetFile).subscribeOn(Schedulers.io())
    }

    private fun installPlugin(tmpFile: File): Single<File> = Single.fromCallable {
        val pluginDescription: PluginDescriptionFile
        try {
            val pluginClassLoader = URLClassLoader(arrayOf(tmpFile.toURI().toURL()))
            pluginDescription = PluginDescriptionFile(pluginClassLoader.getResourceAsStream("plugin.yml"))
        } catch (e: InvalidDescriptionException) {
            throw IllegalStateException("Not a valid plugin")
        }

        val installLocation = findInstallLocation(pluginDescription, !ignoreMainClassNotMatch)

        FileInputStream(tmpFile).channel.use { input ->
            FileOutputStream(installLocation).channel.use { output ->
                input.transferTo(0, input.size(), output)
            }
        }
        tmpFile.delete()
        installLocation
    }.subscribeOn(dev.reactant.reactant.core.ReactantCore.mainThreadScheduler)

    private fun findInstallLocation(pluginDescription: PluginDescriptionFile, requireSameMainClass: Boolean): File {
        val replacingPlugin = Bukkit.getPluginManager().plugins
            .firstOrNull { it.name == pluginDescription.name && it.isEnabled }

        // if is updating plugin
        if (replacingPlugin != null) {
            val newMain = replacingPlugin.description.main
            val oldMain = pluginDescription.main
            if (newMain != oldMain) {
                if (requireSameMainClass) throw PluginMainClassNotMatchException(pluginDescription.name, newMain, oldMain)
                else stdout.out("Ignoring unmatching main class, $newMain (downloaded) $oldMain (current)")
            }
            ReactantCore.instance.onPluginDisable(replacingPlugin)
            Bukkit.getPluginManager().disablePlugin(replacingPlugin) // disable before update
            return File(replacingPlugin.jarLocation.toURI())
        } else {
            // if it is a new plugin, try to find a valid and not conflicting file name
            File("plugins/${pluginDescription.name}.jar").let { if (!it.exists()) return it }
            File("plugins/${pluginDescription.authors}-${pluginDescription.name}.jar").let { if (!it.exists()) return it }
            (0..Int.MAX_VALUE).forEach { num ->
                File("plugins/${pluginDescription.name}($num).jar").let { if (!it.exists()) return it }
            }
        }
        throw IllegalStateException()
    }

    private fun downloadFirstValidRequest(requests: List<Single<ResponseBody>>, downloadTo: File): Single<File> {
        return Observable.fromIterable(requests).flatMapSingle {
            it.doOnSuccess { responseBody ->
                downloadTo.createNewFile()
                downloadTo.sink().buffer().apply {
                    writeAll(responseBody.source())
                    close()
                }
            }.map { true }.onErrorReturn { false }
        }.takeUntil { it }.toList().flatMap {
            if (!downloadTo.exists()) Single.error { Exception("Artifact not found") }
            else Single.just(downloadTo)
        }
    }

    class RetrieveException(val identifier: String, val reason: String) : Exception("Failed to retrieve $identifier, $reason")

    class PluginMainClassNotMatchException(val pluginName: String, val newMain: String, val oldMain: String) :
        Exception("Plugin \"$pluginName\" cannot be updated, main class not matching. $newMain (downloaded) $oldMain (current)")

    private inner class ArtifactInfo(val rawIdentifier: String) {
        val extension = if (rawIdentifier.contains("@")) rawIdentifier.split("@").last() else "jar"
        val groupId: String
        val artifactId: String
        val version: String
        val classifier: String?

        init {
            rawIdentifier.split("@").first().split(":").let {
                if (it.size < 3) throw RetrieveException(rawIdentifier, "Identifier format incorrect")
                groupId = it[0]
                artifactId = it[1]
                version = it[2]
                classifier = if (it.size > 3) it[3] else null
            }
        }

        fun download(repo: String): Single<ResponseBody> =
            if (classifier == null) repositoryRetrieverService.getArtifact(repo, groupId, artifactId, version, extension)
            else repositoryRetrieverService.getArtifact(repo, groupId, artifactId, version, extension, classifier)
    }
}
