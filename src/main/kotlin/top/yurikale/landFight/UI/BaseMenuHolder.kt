package top.yurikale.landFight.ui

import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import top.yurikale.landFight.state.Base

class BaseMenuHolder(val base: Base) : InventoryHolder {
    private val inv: Inventory = Bukkit.createInventory(this, 54, "§0据点战略控制中心")

    override fun getInventory(): Inventory {
        return inv
    }

    fun setupMainMenu() {
        // 这里为后续的二级菜单预留扩展空间
        val infoItem = org.bukkit.inventory.ItemStack(org.bukkit.Material.BEACON)
        val meta = infoItem.itemMeta
        meta?.setDisplayName("§b据点核心状态")
        meta?.lore = listOf("§7据点ID: #${base.id}", "§7当前归属: ${base.ownerTeam?.displayName ?: "中立"}", "", "§a点击下方选项进入战略二级菜单...")
        infoItem.itemMeta = meta

        inv.setItem(13, infoItem)
    }
}