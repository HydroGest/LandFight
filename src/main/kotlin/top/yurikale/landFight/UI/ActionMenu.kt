package top.yurikale.landFight.ui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 所有自定义界面的基类，优雅管理物品与点击事件的绑定
 */
abstract class ActionMenu(
    size: Int,
    title: String
) : InventoryHolder {

    private val inv: Inventory = Bukkit.createInventory(this, size, title)
    // 核心：保存 槽位 -> 点击执行的代码块 (Lambda)
    private val actions = HashMap<Int, (InventoryClickEvent, Player) -> Unit>()

    override fun getInventory(): Inventory = inv

    /**
     * 在界面中放置物品，并（可选）绑定点击事件
     */
    fun setButton(slot: Int, item: ItemStack, action: ((InventoryClickEvent, Player) -> Unit)? = null) {
        inv.setItem(slot, item)
        if (action != null) {
            actions[slot] = action
        }
    }

    /**
     * 填充背景板，防止点击
     */
    fun fillBackground(item: ItemStack) {
        for (i in 0 until inv.size) {
            if (inv.getItem(i) == null) {
                setButton(i, item) // 只有物品，没有动作（纯展示，拦截拿取）
            }
        }
    }

    /**
     * 当这个菜单被点击时，框架自动调用的方法
     */
    fun handleClick(event: InventoryClickEvent) {
        event.isCancelled = true // 默认无条件禁止玩家乱拿物品

        val player = event.whoClicked as? Player ?: return
        // 根据玩家点击的槽位，执行对应的 Lambda 代码块
        actions[event.rawSlot]?.invoke(event, player)
    }
}