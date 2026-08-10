package dev.reactant.reactant.extra.parser

import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.core.dependency.layers.SystemLevel
import dev.reactant.reactant.service.spec.parser.YamlParserService
import io.reactivex.rxjava3.core.Single
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import kotlin.reflect.KClass

@Component
open class SnakeYamlParserService : YamlParserService, SystemLevel {
    protected val options = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = 2
        isPrettyFlow = true
    }
    protected val loaderOptions = LoaderOptions()
    protected val representer = Representer(options)
    protected val yaml = Yaml(CustomClassLoaderConstructor(this.javaClass.classLoader, loaderOptions), representer, options)

    override fun encode(obj: Any, prettyPrint: Boolean): Single<String> =
            Single.defer {
                representer.addClassTag(obj::class.java, Tag.MAP)
                Single.just(yaml.dump(obj))
            }

    override fun <T : Any> decode(encoded: String, modelClass: KClass<T>) =
            Single.defer { Single.just(yaml.loadAs(encoded, modelClass.java)) }

}
