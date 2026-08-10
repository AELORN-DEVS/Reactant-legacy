package dev.reactant.reactant.ui

import dev.reactant.reactant.ui.element.UIElement
import dev.reactant.reactant.ui.event.UIEvent
import dev.reactant.reactant.ui.eventtarget.UIEventTarget
import dev.reactant.reactant.ui.query.UIQueryable
import dev.reactant.reactant.ui.rendering.RenderedView
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

interface UIView : UIEventTarget<UIEvent>, UIQueryable, UIDestroyable {
    val inventory: Inventory
    override val rootElement: UIElement

    /**
     * Inventory view associated by player
     */
    val inventoryViews get() = inventory.viewers.mapNotNull { it as? Player }.map { it to it.openInventory }.toMap()

    fun getElementAt(index: Int) = getElementAt(index % 9, index / 9)

    fun getElementAt(x: Int, y: Int): UIElement?

    fun getIntractableElementAt(index: Int) = getIntractableElementAt(index % 9, index / 9)
    fun getIntractableElementAt(x: Int, y: Int): UIElement?

    /**
     * Update render result and inventory view in next tick
     */
    fun render()


    override fun destroy() {
        this.children.forEach { it.destroy() }
        this.compositeDisposable.dispose()
    }

    val lastRenderResult: RenderedView?

    fun show(player: Player)

    fun setCancelModificationEvents(cancel: Boolean)
}
