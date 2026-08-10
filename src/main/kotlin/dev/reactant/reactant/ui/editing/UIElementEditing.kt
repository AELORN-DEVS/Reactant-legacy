package dev.reactant.reactant.ui.editing

import dev.reactant.reactant.ui.element.UIElement
import dev.reactant.reactant.ui.element.UIElementAttributes
import dev.reactant.reactant.ui.element.UIElementClassList
import dev.reactant.reactant.ui.element.style.UIElementStyleEditing
import dev.reactant.reactant.ui.event.UIElementEvent
import dev.reactant.reactant.ui.event.interact.element.UIElementClickEvent
import dev.reactant.reactant.ui.event.interact.element.UIElementDragEvent
import io.reactivex.rxjava3.core.Observable

interface UIElementEditing<out T : UIElement> : UIElementStyleEditing {
    val element: T;
    var id: String?
    var classList: UIElementClassList
    var attributes: UIElementAttributes

    fun attributes(vararg modifiers: Pair<String, String>) {
        attributes.putAll(modifiers)
    }

    val scheduler get() = element.scheduler

    /**
     * Subscribe an observable, the disposable will automatically add to element's compositeDisposable
     */

    fun <T : Any> subscribe(observable: Observable<T>, onNext: (T) -> Unit) = element.subscribe(observable, onNext)

    val event: Observable<UIElementEvent>
        get() = element.event;

    val onClick: Observable<UIElementClickEvent>
        get() = event.filter { it is UIElementClickEvent }.map { it as UIElementClickEvent }

    @Deprecated("Confusing name", ReplaceWith("onClick"))
    val click
        get() = onClick

    val onDrag: Observable<UIElementDragEvent>
        get() = event.filter { it is UIElementDragEvent }.map { it as UIElementDragEvent }

    @Deprecated("Confusing name", ReplaceWith("onDrag"))
    val drag
        get() = onDrag

}

inline fun <reified T : UIElementEvent> UIElementEditing<UIElement>.event(): Observable<T> {
    return event.filter { it is T }.map { it as T }
}
