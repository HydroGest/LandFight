package top.yurikale.landFight.ui

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.system.GuardData
import top.yurikale.landFight.system.GuardMode

class GuardMenuHolder(
    val guardData: GuardData,
    private val plugin: LandFight
) : ActionMenu(27, "§0⚔ 守卫指令台") {

    fun setupMenu() {
        val bg = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            val m = itemMeta; m?.setDisplayName(" "); itemMeta = m
        }
        fillBackground(bg)
        // 跟随模式按钮 Slot 11
        val followItem = ItemStack(Material.PLAYER_HEAD)
        val fMeta = followItem.itemMeta
        fMeta?.setDisplayName("§a§l跟随模式")
        val isFollowing = guardData.mode == GuardMode.FOLLOW
        val targetName = guardData.followTarget?.let { plugin.server.getPlayer(it)?.name ?: "未知" } ?: "未知"
        fMeta?.lore = listOf(
            "§7当前状态: ${if (isFollowing) "§a跟随 $targetName" else "§c未启用"}",
            "§7效果: 守卫将紧随目标玩家移动",
            "",
            "§e▶ 点击设为跟随你"
        )
        followItem.itemMeta = fMeta
        setButton(11, followItem) { _, player ->
            plugin.guardManager.setFollowMode(guardData, player)
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.2f)
            player.sendMessage("§a守卫已切换为跟随你！")
            setupMenu()
        }
        // 据点防守模式按钮 Slot 15
        val defendItem = ItemStack(Material.SHIELD)
        val dMeta = defendItem.itemMeta
        dMeta?.setDisplayName("§b§l保卫家乡模式")
        val isDefending = guardData.mode == GuardMode.DEFEND
        dMeta?.lore = listOf(
            "§7当前状态: ${if (isDefending) "§a防守据点 #${guardData.baseId}" else "§c未启用"}",
            "§7效果: 等待3秒后，返回据点驻守",
            "",
            "§e▶ 点击设为返回据点"
        )
        defendItem.itemMeta = dMeta
        setButton(15, defendItem) { _, player ->
            plugin.guardManager.setDefendMode(guardData)
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.2f)
            player.sendMessage("§a守卫将在3秒后返回据点！")
            setupMenu()
        }
        // 关闭菜单 Slot 22
        val closeItem = ItemStack(Material.BARRIER)
        val cMeta = closeItem.itemMeta
        cMeta?.setDisplayName("§c§l关闭菜单")
        closeItem.itemMeta = cMeta
        setButton(22, closeItem) { _, player ->
            player.closeInventory()
        }
    }
}