package dev.reactant.reactant.ui.kits.slot

import dev.reactant.reactant.service.spec.server.SchedulerService
import dev.reactant.reactant.ui.UIView
import dev.reactant.reactant.ui.editing.ReactantUIElementEditing
import dev.reactant.reactant.ui.editing.event
import dev.reactant.reactant.ui.element.ReactantUIElement
import dev.reactant.reactant.ui.element.UIElement
import dev.reactant.reactant.ui.element.UIElementName
import dev.reactant.reactant.ui.event.UIElementEvent
import dev.reactant.reactant.ui.event.interact.UIClickEvent
import dev.reactant.reactant.ui.event.interact.UIDragEvent
import dev.reactant.reactant.ui.event.interact.element.UIElementClickEvent
import dev.reactant.reactant.ui.event.inventory.UICloseEvent
import dev.reactant.reactant.ui.kits.ReactantUISingleSlotDisplayElement
import dev.reactant.reactant.ui.kits.ReactantUISingleSlotDisplayElementEditing
import dev.reactant.reactant.ui.kits.slot.event.*
import dev.reactant.reactant.utils.content.item.itemStackOf
import dev.reactant.reactant.utils.delegation.MutablePropertyDelegate
import io.reactivex.rxjava3.core.Observable
import org.bukkit.Material.AIR
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction.*
import org.bukkit.inventory.ItemStack
import kotlin.math.ceil
import kotlin.math.min

@UIElementName("slot")
open class ReactantUISlotElement(allocatedSchedulerService: SchedulerService)
    : ReactantUISingleSlotDisplayElement(allocatedSchedulerService), ItemStorageElement {

    public override var slotItem
        get() = super.slotItem
        set(value) = run { super.slotItem = value }

    private fun hotbarSwap(player: Player, hotbar: Int) {
        val hotbarItem = player.inventory.getItem(hotbar)
        UIElementSlotSwapHotbarItemEvent(this, player, hotbar, hotbarItem, slotItem, PlayerItemStorage(player)).let {
            it.propagateTo(this)
            if (!it.isCancelled) {
                // todo: the handler should take to ui view level for future extend, e.g. using packet-fake bag ui
                val tmp = hotbarItem
                player.inventory.setItem(hotbar, slotItem)
                slotItem = tmp?.let { ItemStack(it) } ?: itemStackOf(AIR)
                pushUpdatedEvent()
            }
        }
    }

    private fun pushUpdatedEvent() {
        UIElementSlotUpdatedEvent(this).propagateTo(this)
    }

    private fun takeItem(player: Player, amount: Int) {
        if (slotItem.type != AIR && player.itemOnCursor.amount == 0) {
            val takingItem = ItemStack(slotItem).also { it.amount = amount }
            takeItem(takingItem, this).let { taken ->
                player.setItemOnCursor(taken)
                pushUpdatedEvent()
            }
        }
    }

    private fun putItem(player: Player, amount: Int) {
        if (player.itemOnCursor.amount != 0 && (slotItem.type == AIR || slotItem.isSimilar(player.itemOnCursor))) {
            val putting = ItemStack(player.itemOnCursor).also { it.amount = amount }
            putItem(putting, PlayerItemStorage(player)).let { returned ->
                // it is not return other item when player putting the item
                assert(returned == null || returned.isSimilar(putting))

                putting.amount -= (returned?.amount ?: 0)
                when {
                    player.itemOnCursor.amount > putting.amount -> {
                        player.itemOnCursor.amount -= putting.amount
                        //refresh view
                        player.setItemOnCursor(player.itemOnCursor)
                    }
                    else -> player.setItemOnCursor(null)
                }
            }
        }
    }

    private fun swapItem(player: Player) {
        if (putAmountLimit(player.itemOnCursor) >= player.itemOnCursor.amount && takeAmountLimit(slotItem.clone()) >= slotItem.amount) {
            UIElementSlotSwapCursorItemEvent(this, player, player.itemOnCursor, slotItem, PlayerItemStorage(player)).let {
                it.propagateTo(this)
                if (!it.isCancelled) {
                    val tmp = ItemStack(player.itemOnCursor)
                    player.setItemOnCursor(slotItem)
                    slotItem = tmp
                    pushUpdatedEvent()
                }
            }
        }
    }

    fun quickTake(playerItemStorage: PlayerItemStorage) {
        (quickPutTarget ?: playerItemStorage).let { currentPutTarget ->
            currentPutTarget.testPutItem(slotItem, this).let { returned ->
                val takingItem = ItemStack(slotItem).also { it.amount - (returned?.amount ?: 0) }
                testTakeItem(takingItem, currentPutTarget)?.let { availableTakingItem ->
                    currentPutTarget.putItem(availableTakingItem, this).let { actualReturned ->
                        val actualPuttedAmount = availableTakingItem.amount - (actualReturned?.amount ?: 0)
                        takingItem.amount = actualPuttedAmount
                        takeItem(takingItem, currentPutTarget)
                    }
                }
            }
        }
    }

    override fun edit() = ReactantUISlotElementEditing(this)

    private fun findQuickPutTarget(finding: UIElement): ItemStorage? = when (finding) {
        is ItemStorageElement -> finding.quickPutTarget
        else -> finding.parent?.let { findQuickPutTarget(it) }
    }

    private var _quickPutTarget: ItemStorage? = null
    override var quickPutTarget: ItemStorage?
        get() = _quickPutTarget ?: parent?.let { findQuickPutTarget(it) }
        set(value) = run { _quickPutTarget = value }

    override var slotIndex: Int = 0

    /**
     * Use to limit how many item can player put
     * Input with the ItemStack that player trying to put
     * Return the maximum amount of this type of item the slot can store, included original item in this slot
     * Return 0 mean input from player is disabled, 1 mean this slot only can store 1 item
     */
    var putAmountLimit: (ItemStack) -> Int = { itemStack -> itemStack.type.maxStackSize }

    /**
     * Use to limit how many item can player take
     * Input with the ItemStack that player trying to take
     * Return the maximum amount of this type of item that player can take
     * Return 0 mean output to player is disabled, 1 mean player need to take the item 64 times to take full stack of item
     * Normally this value should be based on how many item in this slot
     */
    var takeAmountLimit: (ItemStack) -> Int = { itemStack -> itemStack.type.maxStackSize }


    private fun putItems(items: Map<Int, ItemStack>, from: ItemStorage?, isTest: Boolean): Map<Int, ItemStack> {
        var newDisplayItem = slotItem.clone()
        return items.mapNotNull { (i, input) ->

            // the calculated amount that this slot can receive
            val putting: ItemStack = input.clone()
            putting.amount = putting.amount
                    .coerceAtMost(putAmountLimit(putting) - slotItem.let { if (it.type.isAir) 0 else it.amount })
                    .coerceAtLeast(0)

            when {
                // accept full stack item
                newDisplayItem.type.isAir -> Unit
                // calculate accepting amount
                newDisplayItem.isSimilar(putting) ->
                    putting.amount = min(newDisplayItem.maxStackSize - newDisplayItem.amount, putting.amount)
                // put nothing
                else -> putting.amount = 0
            }

            // fire event
            if (putting.amount != 0) {
                UIElementSlotPutItemEvent(this, putting, from, isTest).let { putItemEvent ->
                    putItemEvent.propagateTo(this)

                    if (!putItemEvent.isCancelled) {
                        when (newDisplayItem.type) {
                            AIR -> newDisplayItem = putting
                            else -> newDisplayItem.amount += putting.amount
                        }
                    } else {
                        putting.amount = 0
                    }
                }
            }

            when (putting.amount) {
                // if can't put anything, return all
                0 -> i to input.clone()
                // if put everything, return nothing
                input.amount -> null
                // return partly (at most its original size)
                else -> i to input.clone().also { it.amount -= putting.amount.coerceAtMost(it.amount) }
            }
        }.toMap().also {
            if (!isTest) {
                this.slotItem = ItemStack(newDisplayItem)
                pushUpdatedEvent()
            }
        }
    }

    override fun putItems(items: Map<Int, ItemStack>, from: ItemStorage?): Map<Int, ItemStack> = putItems(items, from, false)

    override fun testPutItems(items: Map<Int, ItemStack>, from: ItemStorage?): Map<Int, ItemStack> = putItems(items, from, true)

    private fun takeItems(wantedItems: Map<Int, ItemStack>, from: ItemStorage?, isTest: Boolean): Map<Int, ItemStack> {
        var newDisplayItem = ItemStack(slotItem)
        return wantedItems.mapNotNull { (i, wanted) ->

            val taking: ItemStack = wanted.clone()
            taking.amount = taking.amount.coerceAtMost(takeAmountLimit(taking)).coerceAtLeast(0)
            when {
                // if similar, fulfill the request as possible
                !newDisplayItem.type.isAir && newDisplayItem.isSimilar(taking) -> taking.amount = min(newDisplayItem.amount, taking.amount)
                // cannot give anything
                else -> taking.amount = 0
            }

            // fire event
            if (taking.amount != 0) {
                UIElementSlotTakeItemEvent(this, taking, from, isTest).let { takeItemEvent ->
                    takeItemEvent.propagateTo(this)

                    if (!takeItemEvent.isCancelled) {
                        assert(taking.type == slotItem.type && taking.amount <= slotItem.amount)
                        newDisplayItem.amount -= taking.amount
                        if (newDisplayItem.amount == 0) newDisplayItem = itemStackOf(AIR)
                    } else {
                        taking.amount = 0
                    }
                }
            }

            when (taking.amount) {
                // if can't take anything, return nothing
                0 -> null
                // if take something, return the item
                else -> i to taking
            }
        }.toMap().also {
            if (!isTest) {
                this.slotItem = ItemStack(newDisplayItem)
                pushUpdatedEvent()
            }
        }
    }

    override fun takeItems(wantedItems: Map<Int, ItemStack>, from: ItemStorage?): Map<Int, ItemStack> = takeItems(wantedItems, from, false)

    override fun testTakeItems(wantedItems: Map<Int, ItemStack>, from: ItemStorage?): Map<Int, ItemStack> = takeItems(wantedItems, from, true)

    override fun iterator(): Iterator<ItemStack> = slotItem
            .let { if (it.type.isAir) emptySequence() else sequenceOf(it) }.iterator()

    init {
        edit().apply {
            event<UIElementClickEvent>().subscribe {
                it.isCancelled = true

                scheduler.next().subscribe {
                    val player = it.bukkitEvent.whoClicked as Player
                    val slotAmount = if (slotItem.type.isAir) 0 else slotItem.amount
                    when (it.bukkitEvent.action) {
                        MOVE_TO_OTHER_INVENTORY -> if (slotAmount != 0) quickTake(PlayerItemStorage(it.bukkitEvent.whoClicked as Player))
                        PICKUP_ALL -> takeItem(player, slotAmount)
                        PICKUP_SOME -> takeItem(player, slotAmount)
                        PICKUP_HALF -> takeItem(player, ceil(slotAmount / 2.0).toInt())
                        PICKUP_ONE -> takeItem(player, min(slotAmount, 1))
                        PLACE_ALL -> putItem(player, player.itemOnCursor.amount)
                        PLACE_SOME -> putItem(player, player.itemOnCursor.amount)
                        PLACE_ONE -> putItem(player, min(player.itemOnCursor.amount, 1))
                        SWAP_WITH_CURSOR -> when {
                            slotAmount != 0 -> swapItem(player)
                            it.bukkitEvent.isLeftClick -> putItem(player, player.itemOnCursor.amount)
                            it.bukkitEvent.isRightClick -> putItem(player, min(player.itemOnCursor.amount, 1))
                        }
                        HOTBAR_SWAP -> hotbarSwap(player, it.bukkitEvent.hotbarButton)
                        else -> run { }
                    }
                }
            }
        }
    }


}

fun UIView.setAsSlotView(playerInventoryQuickPutTarget: ItemStorage, dropCursorWhenClose: Boolean = true) {
    this.event.ofType(UIDragEvent::class.java).subscribe { it.isCancelled = true }
    this.event.ofType(UIClickEvent::class.java).filter { it is UIElementEvent }.subscribe { it.isCancelled = true }
    this.event.ofType(UIClickEvent::class.java).filter { it !is UIElementEvent }.subscribe {
        when (it.bukkitEvent.action) {
            COLLECT_TO_CURSOR -> it.isCancelled = true
            MOVE_TO_OTHER_INVENTORY -> {
                it.isCancelled = true
                it.bukkitEvent.currentItem?.let { movingItem ->
                    playerInventoryQuickPutTarget.putItem(movingItem, PlayerItemStorage(it.bukkitEvent.whoClicked as Player)).let { returned ->
                        it.bukkitEvent.whoClicked.inventory.setItem(it.bukkitEvent.slot, returned)
                    }
                }
            }
            else -> run { }
        }
    }
    if (dropCursorWhenClose) this.event.ofType(UICloseEvent::class.java).subscribe {
        if (!it.player.itemOnCursor.type.isAir) it.player.world.dropItem(it.player.location, it.player.itemOnCursor)
    }
}

open class ReactantUISlotElementEditing<out T : ReactantUISlotElement>(element: T)
    : ReactantUISingleSlotDisplayElementEditing<T>(element) {
    var slotItem by MutablePropertyDelegate(element::slotItem)
    var quickPutTarget by MutablePropertyDelegate(element::quickPutTarget)
    var slotIndex by MutablePropertyDelegate(element::slotIndex)
    var putAmountLimit by MutablePropertyDelegate(element::putAmountLimit)
    var takeAmountLimit by MutablePropertyDelegate(element::takeAmountLimit)

    override val onClick: Observable<UIElementClickEvent>
        get() = super.onClick

    val onSlotPut get() = event<UIElementSlotPutItemEvent>()
    val onSlotTake get() = event<UIElementSlotTakeItemEvent>()
    val onSlotUpdate get() = event<UIElementSlotUpdatedEvent>()
}

fun ReactantUIElementEditing<ReactantUIElement>.slot(creation: ReactantUISlotElementEditing<ReactantUISlotElement>.() -> Unit) {
    element.children.add(ReactantUISlotElement(element.allocatedSchedulerService)
            .also { ReactantUISlotElementEditing(it).apply(creation) })
}


