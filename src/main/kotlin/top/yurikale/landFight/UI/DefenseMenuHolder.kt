package top.yurikale.landFight.ui

import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base

class DefenseMenuHolder(
    val base: Base,
    private val plugin: LandFight
) : ActionMenu(36, "§0🛡 城防管理中心") {

    fun setupMenu() {
        val upgradeItem = ItemStack(if (base.level >= 3) Material.NETHER_STAR else Material.ANVIL)
        val upMeta = upgradeItem.itemMeta
        when (base.level) {
            1 -> {
                upMeta?.setDisplayName("§a§l▲ 升级据点至 Lv.2")
                upMeta?.lore = listOf(
                    "§7消耗: §c10个铜锭",
                    "§7效果: 核心血量上限 §f50 -> 100",
                    "§7效果: 核心自然回血 §f1滴/3秒 -> 1滴/2秒",
                    "§b当前Lv.1权益：工业产能10/10秒，羊回血3秒1点",
                    "",
                    "§e▶ 点击消耗物资进行升级"
                )
            }
            2 -> {
                upMeta?.setDisplayName("§a§l▲ 升级据点至 Lv.3")
                upMeta?.lore = listOf(
                    "§7消耗: §f30个铁锭",
                    "§7效果: 核心血量上限 §f100 -> 150",
                    "§7效果: 核心自然回血 §f1滴/2秒 -> 1滴/1秒",
                    "§b当前Lv.2权益：工业产能20/10秒，羊回血2秒1点",
                    "",
                    "§e▶ 点击消耗物资进行升级"
                )
            }
            else -> {
                upMeta?.setDisplayName("§6§l★ 据点已达满级 (Lv.3)")
                upMeta?.lore = listOf(
                    "§7核心血量上限: §f150",
                    "§7核心自然回血: §f1滴/1秒",
                    "§b当前Lv.3权益：工业产能40/10秒，羊回血1秒1点"
                )
            }
        }
        upgradeItem.itemMeta = upMeta

        setButton(11, upgradeItem) { _, player ->
            when (base.level) {
                1 -> {
                    if (consumeMaterial(player, Material.COPPER_INGOT, 10)) {
                        executeUpgrade(player, 2, 100.0)
                    } else {
                        player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                        player.sendMessage("§c物资不足！升级需要 10 个铜锭 (Copper Ingot)。")
                    }
                }
                2 -> {
                    if (consumeMaterial(player, Material.IRON_INGOT, 30)) {
                        executeUpgrade(player, 3, 150.0)
                    } else {
                        player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                        player.sendMessage("§c物资不足！升级需要 30 个铁锭 (Iron Ingot)。")
                    }
                }
                else -> {
                    player.sendMessage("§e据点已是最高等级，无法继续升级！")
                }
            }
        }

        val guardCost = plugin.guardManager.calculateCost(base)
        val guardCount = plugin.guardManager.guards.values.count { it.baseId == base.id }

        val guardItem = ItemStack(Material.ZOMBIE_SPAWN_EGG)
        val guardMeta = guardItem.itemMeta
        guardMeta?.setDisplayName("§e§l⚔ 招募据点守卫")
        guardMeta?.lore = listOf(
            "§7当前守卫存活: §a$guardCount §7名",
            "§7招募价格: §c${guardCost}个铁锭",
            "§7效果: 召唤1名卫道士和1名掠夺者",
            "§c[!] 每次招募后价格暴涨，5分钟后回落至10铁",
            "",
            "§e▶ 点击消耗物资招募守卫"
        )
        guardItem.itemMeta = guardMeta

        setButton(15, guardItem) { _, player ->
            if (consumeMaterial(player, Material.IRON_INGOT, guardCost)) {
                plugin.guardManager.spawnGuards(base, player)
                player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f)
                player.sendMessage("§a成功招募了2名守卫！他们将跟随你作战。")
            } else {
                player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                player.sendMessage("§c物资不足！招募需要 ${guardCost} 个铁锭。")
            }
            setupMenu()
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

    // 辅助升级方法
    private fun executeUpgrade(player: Player, newLevel: Int, maxHp: Double) {
        base.level = newLevel
        val sheep = plugin.server.getEntity(base.sheepEntityId ?: return) as? org.bukkit.entity.Sheep ?: return
        sheep.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHp
        sheep.health = maxHp
        plugin.structurePlacer.refreshBaseVisual(base, plugin.structurePlacer.isBaseCapital(base.id))

        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
        player.sendMessage("§a【城防升级】 据点成功升级至 Lv.$newLevel！核心血量上限已提升。")

        val playerTeam = plugin.teamManager.getPlayerTeam(player)
        val alertMsg = "§a【城防升级】 队友 §f${player.name} §a将据点 §e#${base.id} §a升级至 §6Lv.${newLevel} §a！"
        org.bukkit.Bukkit.getOnlinePlayers().forEach { p ->
            if (plugin.teamManager.getPlayerTeam(p) == playerTeam) {
                p.sendMessage(alertMsg)
                p.playSound(p.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f)
            }
        }
        setupMenu() // 刷新当前UI
    }

    // 辅助扣除物资方法
    private fun consumeMaterial(player: Player, material: Material, amount: Int): Boolean {
        var count = 0
        player.inventory.contents.forEach { item ->
            if (item != null && item.type == material) count += item.amount
        }
        if (count < amount) return false

        var remaining = amount
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (item.type == material) {
                if (item.amount > remaining) {
                    item.amount -= remaining
                    break
                } else {
                    remaining -= item.amount
                    player.inventory.setItem(i, null)
                }
            }
        }
        return true
    }
}