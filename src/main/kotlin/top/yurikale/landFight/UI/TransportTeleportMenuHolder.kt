package top.yurikale.landFight.ui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base
import top.yurikale.landFight.team.TeamColor

class TransportTeleportMenuHolder(
    val base: Base,
    private val plugin: LandFight
) : ActionMenu(54, "§0⚡ 战略快速传送网络") {

    fun setupMenu() {
        val graph = plugin.structurePlacer.networkGraph
        val myTeam = base.ownerTeam ?: TeamColor.NEUTRAL

        val gridMap = mutableMapOf<Int, Base>()
        plugin.structurePlacer.activeBases.values.forEach { b ->
            val col = (b.location.blockX + 750) / 250
            val row = (b.location.blockZ + 750) / 250
            if (col in 0..5 && row in 0..5) gridMap[row * 9 + col] = b
        }

        // 渲染左侧地图
        for (row in 0..5) {
            for (col in 0..5) {
                val slot = row * 9 + col
                val targetBase = gridMap[slot]

                if (targetBase == null) {
                    val emptyBg = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
                        val m = itemMeta; m?.setDisplayName("§8[无网区域]"); itemMeta = m
                    }
                    setButton(slot, emptyBg)
                    continue
                }

                // 核心计算连通性
                val isSelf = targetBase.id == base.id
                val isAlly = targetBase.ownerTeam == myTeam
                val isConnectedNetwork = graph.isConnected(base.id, targetBase.id)

                val mat = when {
                    isSelf -> getGlass(targetBase.ownerTeam)
                    plugin.structurePlacer.isBaseCapital(targetBase.id) -> getBanner(targetBase.ownerTeam)
                    else -> getWool(targetBase.ownerTeam)
                }

                val item = ItemStack(mat)
                val meta = item.itemMeta
                meta?.setDisplayName("${targetBase.ownerTeam?.colorCode}据点 #${targetBase.id}")

                // 细化且醒目的 Lore
                val lore = mutableListOf<String>()
                lore.add("§7位置坐标: §f[${targetBase.location.blockX}, ${targetBase.location.blockZ}]")
                lore.add("§8§m-------------------------")

                if (isSelf) {
                    lore.add("§c▶ 无法传送：你正身处此据点")
                } else if (!isAlly) {
                    lore.add("§c[🔒 权限拒绝] 非己方控制区")
                    lore.add("§7无法向中立或敌对据点折跃。")
                } else if (!isConnectedNetwork) {
                    lore.add("§c[⚠ 物理断网] 处于网络孤岛")
                    lore.add("§7你需要先在【交通线管理】中")
                    lore.add("§7铺设线路，将其接入据点网络。")
                } else {
                    lore.add("§a[📡 网络畅通] 该据点已接入本站网络")
                    lore.add("§e▶ 点击立刻开始折跃向该据点")
                    lore.add("§8(启动需引导 3 秒，期间切勿走动)")

                    // 仅连通的己方可用点拥有附魔光泽，极为显眼
                    meta?.addEnchant(Enchantment.POWER, 1, true)
                    meta?.addItemFlags(ItemFlag.HIDE_ENCHANTS)

                    setButton(slot, item) { _, player ->
                        player.closeInventory()
                        player.sendMessage("§b【折跃启动】 目标锁定 #${targetBase.id}，3秒后传送，请勿走动...")
                        player.playSound(player.location, org.bukkit.Sound.BLOCK_PORTAL_TRIGGER, 0.5f, 1.5f)

                        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            if (player.isOnline && !player.isDead) {
                                val tpLoc = targetBase.location.clone().add(0.0, 3.0, 0.0)
                                player.teleport(tpLoc)
                                player.sendMessage("§a【折跃完毕】 你已抵达 ${targetBase.ownerTeam?.colorCode}据点 #${targetBase.id}！")
                                player.playSound(player.location, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
                            }
                        }, 60L)
                    }
                }

                meta?.lore = lore
                item.itemMeta = meta

                if (!isSelf && isAlly && isConnectedNetwork) {
                    // Button action has already been set in the block above
                } else {
                    setButton(slot, item) // 不可点击的纯展示
                }
            }
        }

        // 渲染边界
        val border = ItemStack(Material.YELLOW_STAINED_GLASS_PANE).apply {
            val meta = itemMeta; meta?.setDisplayName("§e[边界线]"); itemMeta = meta
        }
        for (row in 0..5) setButton(row * 9 + 6, border)

        // 右下角返回按钮
        val backBtn = ItemStack(Material.ARROW).apply {
            val m = itemMeta; m?.setDisplayName("§c⬅ 返回上级枢纽"); itemMeta = m
        }
        setButton(53, backBtn) { _, player ->
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            val menu = TransportMenuHolder(base, plugin)
            menu.setupMenu()
            player.openInventory(menu.inventory)
        }

        val bg = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            val m = itemMeta; m?.setDisplayName(" "); itemMeta = m
        }
        fillBackground(bg)
    }

    private fun getWool(team: TeamColor?) = when (team) { TeamColor.RED -> Material.RED_WOOL; TeamColor.BLUE -> Material.BLUE_WOOL; else -> Material.GRAY_WOOL }
    private fun getGlass(team: TeamColor?) = when (team) { TeamColor.RED -> Material.RED_STAINED_GLASS; TeamColor.BLUE -> Material.BLUE_STAINED_GLASS; else -> Material.GRAY_STAINED_GLASS }
    private fun getBanner(team: TeamColor?) = when (team) { TeamColor.RED -> Material.RED_BANNER; TeamColor.BLUE -> Material.BLUE_BANNER; else -> Material.GRAY_BANNER }
}