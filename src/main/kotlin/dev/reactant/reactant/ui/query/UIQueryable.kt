package dev.reactant.reactant.ui.query

import com.google.common.collect.ImmutableSet
import com.steadystate.css.parser.CSSOMParser
import com.steadystate.css.parser.SACParserCSS3
import dev.reactant.reactant.ui.element.UIElement
import dev.reactant.reactant.ui.element.UIElementChildren
import dev.reactant.reactant.ui.element.UIElementName
import org.w3c.css.sac.InputSource
import java.io.StringReader
import kotlin.reflect.full.findAnnotation


interface UIQueryable {
    val children: UIElementChildren
    val rootElement: UIElement?
    val parent: UIElement?

    val id: String? get() = null;

    val name: String get() = this::class.findAnnotation<UIElementName>()?.name ?: "unnamed-el"
    val namespace: String get() = this::class.findAnnotation<UIElementName>()?.namespace ?: ""
    val fullName: String get() = "$name:$namespace"

    val path: String get() = (parent?.path?.let { "$it>" } ?: "") + name

    /**
     * @return 0 if it is this itself, positive value if it is a child of this, negative value if it is a parent of it, nothing if they do not have parent/child relation.
     */
    fun distanceTo(queryable: UIQueryable): Int? {
        if (queryable == this) return 0
        else {
            fun checkChildrenDistance(parent: UIQueryable, children: UIQueryable): Int? {
                var result = 1;
                var checking = children.parent
                while (checking != null) {
                    if (checking == parent) return result
                    checking = checking.parent
                    result++;
                }
                return null;
            }

            val childrenDistance = checkChildrenDistance(this, queryable);
            return childrenDistance ?: checkChildrenDistance(queryable, this)?.let { -it };
        }
    }

    val recursiveChildren: ImmutableSet<UIElement>
        get() {
            fun walkChildren(el: UIQueryable): Set<UIElement> {
                return el.children.union(el.children.flatMap { walkChildren(it) }).toSet();
            }
            return ImmutableSet.copyOf(walkChildren(this));
        }

    /**
     * Find by predicate, return first or null
     */
    fun UIQueryable.firstElement(predicate: (UIElement) -> Boolean): UIElement? =
            children.firstOrNull(predicate) ?: children.mapNotNull { it.firstElement(predicate) }.firstOrNull()

    /**
     * Find closest children, not including this itself
     */
    fun closestChild(selector: String): UIElement? = querySelectorAll(selector)
            .asSequence()
            .map { it to this.distanceTo(it) }
            .filter { it.second != null && it.second!! > 0 }
            .sortedBy { it.second }
            .map { it.first }.firstOrNull()

    fun closest(selector: String): UIElement? = rootElement?.querySelectorAll(selector)
            ?.asSequence()
            ?.map { it to this.distanceTo(it) }
            ?.filter { it.second != null && it.second!! < 0 }
            ?.sortedByDescending { it.second }
            ?.map { it.first }?.firstOrNull()


    fun querySelectorAll(selector: String): Set<UIElement> = selectElements(this, parser.parseSelectors(InputSource(StringReader(selector))))


    /**
     * Check is the element match the selector in parent's view
     * Root element will always return false
     */
    fun matches(selector: String): Boolean = this.rootElement?.querySelectorAll(selector)?.contains(this) ?: false

    companion object {
        var parser = CSSOMParser(SACParserCSS3());
    }
}

inline fun <reified T : UIElement> UIQueryable.querySelector(selector: String): T? = this.querySelectorAll(selector).firstOrNull() as T?
inline fun <reified T : UIElement> UIQueryable.getElementById(id: String): T? = this.firstElement { it.id == id } as T?

