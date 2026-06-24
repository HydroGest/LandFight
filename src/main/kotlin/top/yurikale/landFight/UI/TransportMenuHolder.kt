package top.yurikale.landFight.ui

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base

class TransportMenuHolder(
    val base: Base,
    private val plugin: LandFight
) : ActionMenu(36, "§0🛤 交通网络枢纽") {

    fun setupMenu() {
        val graph = plugin.structurePlacer.networkGraph
        // 获取当前据点的直连邻居
        val neighbors = graph.getNeighbors(base.id)

        // 1. 交通线管理按钮 (Slot 12)
        val manageItem = ItemStack(Material.MINECART)
        val manageMeta = manageItem.itemMeta
        manageMeta?.setDisplayName("§e§l🛤 交通线规划与管理")
        manageMeta?.lore = listOf(
            "§7当前直连相邻据点: §f${neighbors.joinToString(", ") { "#$it" }.ifEmpty { "无" }}",
            "",
            "§a▶ 点击进入：连接或切断与其他据点的网络边",
        )
        manageItem.itemMeta = manageMeta

        setButton(11, manageItem) { _, player ->
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.2f)
            // 跳转到连线管理三级菜单，默认处于 IDLE 状态
            val manageMenu = TransportManageMenuHolder(base, plugin)
            manageMenu.setupMenu()
            player.openInventory(manageMenu.inventory)
        }

        // 2. 快速传送按钮 (Slot 14)
        val tpItem = ItemStack(Material.ENDER_PEARL)
        val tpMeta = tpItem.itemMeta
        tpMeta?.setDisplayName("§b§l⚡ 战略快速网络传送")
        tpMeta?.lore = listOf(
            "§7利用已连通的交通网络进行折跃",
            "§7限制: §c仅可传送到网络连通的己方据点",
            "",
            "§a▶ 点击进入：选择目标据点进行快速传送",
        )
        tpItem.itemMeta = tpMeta

        setButton(15, tpItem) { _, player ->
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.2f)
            // 跳转到传送网络三级菜单
            val tpMenu = TransportTeleportMenuHolder(base, plugin)
            tpMenu.setupMenu()
            player.openInventory(tpMenu.inventory)
        }

        // 3. 返回主菜单按钮 (Slot 31)
        val backItem = ItemStack(Material.ARROW)
        val backMeta = backItem.itemMeta
        backMeta?.setDisplayName("§c§l⬅ 返回战略控制中心")
        backItem.itemMeta = backMeta

        setButton(31, backItem) { _, player ->
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            val baseMenu = BaseMenuHolder(base, plugin)
            baseMenu.setupMainMenu()
            player.openInventory(baseMenu.inventory)
        }

        // 4. 填充背景墙
        val background = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val bgMeta = background.itemMeta
        bgMeta?.setDisplayName(" ")
        background.itemMeta = bgMeta
        fillBackground(background)
    }
}