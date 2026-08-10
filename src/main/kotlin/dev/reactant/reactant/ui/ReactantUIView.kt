package dev.reactant.reactant.ui

import dev.reactant.reactant.core.ReactantCore
import dev.reactant.reactant.service.spec.server.SchedulerService
import dev.reactant.reactant.ui.element.UIElement
import dev.reactant.reactant.ui.element.UIElementChildren
import dev.reactant.reactant.ui.event.UIEvent
import dev.reactant.reactant.ui.event.interact.UIInteractEvent
import dev.reactant.reactant.ui.query.UIQueryable
import dev.reactant.reactant.ui.query.selectElements
import dev.reactant.reactant.ui.rendering.ReactantRenderedView
import dev.reactant.reactant.ui.rendering.RenderedView
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.w3c.css.sac.InputSource
import java.io.StringReader

class ReactantUIView(val allocatedSchedulerService: SchedulerService, private val showPlayerFunc: (ReactantUIView, Player) -> Unit,
                     val title: String, val height: Int) : UIView {

    override val compositeDisposable: CompositeDisposable = CompositeDisposable()
    override val scheduler: SchedulerService = UIDestroyable.convertToDestroyableScheduler(this, allocatedSchedulerService)

    override fun show(player: Player) = lastRenderResult.let {
        if (it == null) {
            updateView()
        }
    }.also { showPlayerFunc(this, player) }

    private var isCancelModificationEvents: Boolean = false
    override fun setCancelModificationEvents(cancel: Boolean) {
        this.isCancelModificationEvents = cancel
    }

    override val event = PublishSubject.create<UIEvent>()

    var scheduledUpdate: Disposable? = null

    var width = 9

    private var _inventory: Inventory? = null;
    override val inventory: Inventory
        get() {
            if (_inventory == null) {
                _inventory = Bukkit.createInventory(null, width * height, title)
            }
            return _inventory!!
        }

    override val rootElement = ViewInventoryContainerElement(this, allocatedSchedulerService)

    override fun querySelectorAll(selector: String): Set<UIElement> = selectElements(rootElement, UIQueryable.parser.parseSelectors(InputSource(StringReader(selector))))

    override val children: UIElementChildren = LinkedHashSet(setOf(rootElement))
    override val parent: UIElement? = null

    override var lastRenderResult: RenderedView? = null

    override fun render() {
        scheduleUpdate()
    }

    override fun getElementAt(x: Int, y: Int): UIElement? {
        if (lastRenderResult == null) throw IllegalStateException("UI never be rendered")
        return lastRenderResult!!.layerResult[x to y]?.last()
    }

    override fun getIntractableElementAt(x: Int, y: Int): UIElement? {
        if (lastRenderResult == null) throw IllegalStateException("UI never be rendered")
        return lastRenderResult!!.layerResult[x to y]?.last { it.computedInteractEvents }
    }

    private fun scheduleUpdate() {
        if (this.scheduledUpdate == null) this.scheduledUpdate = allocatedSchedulerService.next()
                .observeOn(ReactantCore.mainThreadScheduler)
                .subscribe(this::updateView)
    }

    private fun updateView() {
        rootElement.computeStyle()
        lastRenderResult = ReactantRenderedView(this)
        lastRenderResult!!.result
                .map { (position, item) -> (position.second * 9 + position.first) to item }
                // filter out the slots which have different ItemStack
                .filter { (slotIndex, item) -> inventory.getItem(slotIndex)?.equals(item)?.not() ?: true }
                .forEach { (slotIndex, item) -> inventory.setItem(slotIndex, item) }
        scheduledUpdate = null
    }


    init {
        event<UIInteractEvent>().subscribe { if (isCancelModificationEvents) it.isCancelled = true }
    }
}

inline fun <reified T : UIEvent> ReactantUIView.event(): Observable<T> {
    return event.filter { it is T }.map { it as T }
}
