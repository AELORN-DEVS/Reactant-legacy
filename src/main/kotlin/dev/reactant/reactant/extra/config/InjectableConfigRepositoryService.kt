package dev.reactant.reactant.extra.config

import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.core.dependency.ProviderManager
import dev.reactant.reactant.core.dependency.injection.Provide
import dev.reactant.reactant.core.dependency.layers.SystemLevel
import dev.reactant.reactant.extra.config.exception.ConfigDecodeException
import dev.reactant.reactant.extra.config.type.MultiConfigs
import dev.reactant.reactant.extra.config.type.SharedConfig
import dev.reactant.reactant.extra.parser.GsonJsonParserService
import dev.reactant.reactant.extra.parser.SnakeYamlParserService
import dev.reactant.reactant.extra.parser.Toml4jTomlParserService
import dev.reactant.reactant.service.spec.config.Config
import dev.reactant.reactant.service.spec.config.ConfigService
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.toObservable
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.jvm.jvmErasure

@Component
private class InjectableConfigRepositoryService(
        private val jsonParserService: GsonJsonParserService,
        private val yamlParserService: SnakeYamlParserService,
        private val tomlParserService: Toml4jTomlParserService,
        private val configService: ConfigService,
        private val providerManager: ProviderManager
) : SystemLevel {
    private val configParserDecider = ConfigParserDecider(jsonParserService, yamlParserService, tomlParserService)

    private class DelegatedSharedConfig(val config: Config<Any>) : SharedConfig<Any>, Config<Any> by config

    @Provide(".*", true)
    private fun getConfigRepository(kType: KType, path: String): MultiConfigs<Any> {
        @Suppress("UNCHECKED_CAST")
        val configClass = kType.arguments.first().type!!.jvmErasure as KClass<Any>
        return MultiConfigsImpl(File(path), kType.arguments.first().type!!, configParserDecider)
    }

    private inner class MultiConfigsImpl<T : Any>(
            override val configFolder: File, val kType: KType, val configParserDecider: ConfigParserDecider
    ) : MultiConfigs<T> {
        init {
            when {
                !configFolder.exists() -> configFolder.mkdirs()
                !configFolder.isDirectory -> throw IllegalArgumentException("$configFolder is not a folder")
            }
        }

        override fun get(relativePath: String): Maybe<Config<T>> =
                configService.get(configParserDecider.getParserByPath(relativePath), kType, "${configFolder.absolutePath}/$relativePath")

        override fun getOrDefault(relativePath: String, defaultContentCallable: () -> T): Single<Config<T>> =
                configService.getOrDefault(configParserDecider.getParserByPath(relativePath), kType, "${configFolder.absolutePath}/$relativePath", defaultContentCallable)

        override fun getOrPut(relativePath: String, defaultContentCallable: () -> T): Single<Config<T>> =
                configService.getOrPut(configParserDecider.getParserByPath(relativePath), kType, "${configFolder.absolutePath}/$relativePath", defaultContentCallable)

        override fun getAll(recursively: Boolean): Observable<Config<T>> =
                Observable.defer { configFolder.walkTopDown().filter { it.isFile }.toObservable() }
                        .flatMapSingle { file ->
                            configService.get<T>(configParserDecider.getParserByPath(file.absolutePath), kType, file.absolutePath)
                                    .switchIfEmpty(Single.error { IllegalStateException() }).materialize().map { file.path to it }
                        }.toList()
                        .flatMap { result ->
                            when {
                                result.any { it.second.isOnError } -> Single.error { ConfigDecodeException(result.filter { it.second.isOnError }.map { it.first to it.second.error!! }.toMap()) }
                                else -> Single.just(result.mapNotNull { it.second.value })
                            }
                        }.flattenAsObservable { it }

        @Suppress("UNCHECKED_CAST")
        override val modelClass: KClass<T>
            get() = kType.jvmErasure as KClass<T>

    }
}
