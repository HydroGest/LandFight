package top.yurikale.landFight.ui

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base
import top.yurikale.landFight.team.TeamColor

class BaseMenuHolder(val base: Base, private val plugin: LandFight) : ActionMenu(54, "§0据点战略控制中心") {

    fun setupMainMenu() {
        val graph = plugin.structurePlacer.networkGraph
        val baseDegree = graph.getNeighbors(base.id).size

        val myTeam = base.ownerTeam ?: TeamColor.NEUTRAL
        val capitalLoc = plugin.teamManager.teamsCapitals[myTeam]
        val isCapital = capitalLoc != null &&
                capitalLoc.world?.name == base.location.world?.name &&
                capitalLoc.blockX == base.location.blockX &&
                capitalLoc.blockY == base.location.blockY &&
                capitalLoc.blockZ == base.location.blockZ

        // 1. 配置据点核心状态 (Slot 13)
        val infoItem = ItemStack(Material.BEACON)
        val meta = infoItem.itemMeta
        meta?.setDisplayName("§b§l中心核心状态")
        meta?.lore = listOf(
            "§7据点编号: §f#${base.id}",
            "§7当前归属: ${myTeam.colorCode}${myTeam.displayName}",
            "§7据点类型: ${if (isCapital) "§c§l★ 队大本营" else "§a标准前线据点"}",
            "§7网络节点度: §e${baseDegree} §7(当前直连交通线数量)",
            "",
            "§a▶ 点击下方选项进入战略二级菜单..."
        )
        infoItem.itemMeta = meta
        setButton(13, infoItem) // 纯展示，不绑动作

        // 2. 城防菜单入口 (Slot 29)
        val defenseItem = ItemStack(Material.NETHERITE_CHESTPLATE)
        val defMeta = defenseItem.itemMeta
        defMeta?.setDisplayName("§e§l🛡 城防管理中心")
        defMeta?.lore = listOf(
            "§7据点当前等级: §bLv.${base.level}",
            "§7绝对防御状态: ${if (base.inAbsoluteProtection) "§a活跃中" else "§7未开启"}",
            "",
            "§e▶ 点击进入升级据点防御、招募守卫"
        )
        defenseItem.itemMeta = defMeta

        // 绑定跳转事件
        setButton(29, defenseItem) { _, player ->
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.2f)
            val defenseMenu = DefenseMenuHolder(base, plugin)
            defenseMenu.setupMenu()
            player.openInventory(defenseMenu.inventory)
        }

        // 3. 交通菜单入口 (Slot 31)
        val transportItem = ItemStack(Material.RAIL)
        val traMeta = transportItem.itemMeta
        traMeta?.setDisplayName("§b§l🛤 交通网络枢纽")
        traMeta?.lore = listOf(
            "§7直连邻居: §f${graph.getNeighbors(base.id).joinToString(", ") { "#$it" }.ifEmpty { "无" }}",
            "",
            "§e▶ 点击进入规划/掐断与其它据点的战略连线"
        )
        transportItem.itemMeta = traMeta

        setButton(31, transportItem) { _, player ->
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.2f)
            val transportMenu = TransportMenuHolder(base, plugin)
            transportMenu.setupMenu()
            player.openInventory(transportMenu.inventory)
        }

        // 4. 工业菜单入口 (Slot 33)
        val industryItem = ItemStack(Material.FURNACE)
        val indMeta = industryItem.itemMeta
        indMeta?.setDisplayName("§a§l⚒ 工业资源产出")
        indMeta?.lore = listOf(
            "§7木材产出权重: §e${String.format("%.2f", base.woodWeight)}",
            "§7铁矿产出权重: §e${String.format("%.2f", base.ironWeight)}",
            "",
            "§e▶ 点击进入扩建资源井、调配后勤产出速度"
        )
        industryItem.itemMeta = indMeta

        setButton(33, industryItem) { _, player ->
            player.sendMessage("§e[工业产出] 模块开发中...")
        }

        // 5. 填充背景（防止乱拿物品）
        val background = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val bgMeta = background.itemMeta
        bgMeta?.setDisplayName(" ")
        background.itemMeta = bgMeta
        fillBackground(background)
    }
}