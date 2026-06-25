package top.yurikale.landFight.ui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base

class IndustryMenuHolder(val base: Base, private val plugin: LandFight) : ActionMenu(54, "§0⚒ 据点工业系统") {

    fun setupMenu() {
        // ================= 背景与装饰 =================
        val bgDark = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            val m = itemMeta; m?.setDisplayName(" "); itemMeta = m
        }
        val bgCyan = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            val m = itemMeta; m?.setDisplayName("§3[工业生产线]"); itemMeta = m
        }
        fillBackground(bgDark)
        for (i in 9..17) setButton(i, bgCyan) // 生产线传送带视觉分隔

        // ================= 顶部：地质扫描报告 (Slot 0-8) =================
        val scanRadar = ItemStack(Material.COMPASS)
        val radarMeta = scanRadar.itemMeta
        radarMeta?.setDisplayName("§b§l🌐 地质勘测报告")
        val radarLore = mutableListOf("§7当前环境已探明以下资源分布：", "§8§m-------------------------")

        if (!base.isScanned || base.resourceWeights.isEmpty()) {
            radarLore.add("§c未扫描或该地区极度贫瘠，无法产出。")
        } else {
            // 将权重转换为百分比展示
            base.resourceWeights.entries.sortedByDescending { it.value }.forEach { (mat, weight) ->
                val percentStr = String.format("%.2f%%", weight * 100)
                radarLore.add("§7▶ §f${getMaterialName(mat)}: §e$percentStr")
            }
        }
        radarLore.add("§8§m-------------------------")
        radarLore.add("§7* 剩余百分比为岩层与废土 (空气)")
        radarMeta?.lore = radarLore
        scanRadar.itemMeta = radarMeta
        setButton(4, scanRadar) // 放在正中间

        // ================= 中部：工业存储库 (Slot 19-25) =================
        var slotIndex = 19
        if (base.resourceStorage.isEmpty() || base.resourceStorage.values.all { it == 0 }) {
            val emptyStorage = ItemStack(Material.MINECART)
            val em = emptyStorage.itemMeta
            em?.setDisplayName("§7[仓储为空]")
            em?.lore = listOf("§7工业机器正在努力运转中...", "§7(也可能是据点处于中立停工状态)")
            emptyStorage.itemMeta = em
            setButton(22, emptyStorage)
        } else {
            base.resourceStorage.filter { it.value > 0 }.forEach { (mat, count) ->
                if (slotIndex > 25) return@forEach // 限制最多展示 7 种产物

                val item = ItemStack(mat)
                val meta = item.itemMeta
                meta?.setDisplayName("§a§l${getMaterialName(mat)}")
                meta?.lore = listOf(
                    "§7当前库存: §f$count 个",
                    "§7产出进度(累加器): §e${String.format("%.1f%%", (base.resourceAccumulator[mat] ?: 0.0) * 100)}",
                    "§8§m-------------------------",
                    "§e▶ 点击提取当前所有库存"
                )
                item.itemMeta = meta

                setButton(slotIndex, item) { _, player -> extractItem(player, mat) }
                slotIndex++
            }
        }

        // ================= 底部：操作台 (Slot 45-53) =================
        // 核心控制台
        val coreInfo = ItemStack(Material.BLAST_FURNACE)
        val coreMeta = coreInfo.itemMeta
        coreMeta?.setDisplayName("§e§l🏭 工业产能引擎")
        val outCap = when(base.level) { 1->10; 2->20; 3->40; else->0 }
        coreMeta?.lore = listOf(
            "§7据点工业等级: §bLv.${base.level}",
            "§7总吞吐功率: §a$outCap 方块/10秒",
            "§8§m-------------------------",
            "§7产能说明：每10秒系统会执行 ${outCap} 次挖掘",
            "§7每次挖掘有几率获得上方雷达显示的物资。"
        )
        coreInfo.itemMeta = coreMeta
        setButton(45, coreInfo)

        // 一键提取全部按钮
        val extractAllBtn = ItemStack(Material.HOPPER)
        val exMeta = extractAllBtn.itemMeta
        exMeta?.setDisplayName("§6§l📦 一键提取全部物资")
        exMeta?.lore = listOf("§7将当前仓储内所有物资提取到背包")
        extractAllBtn.itemMeta = exMeta
        setButton(49, extractAllBtn) { _, player -> extractAll(player) }

        // 返回按钮
        val backBtn = ItemStack(Material.ARROW)
        val backMeta = backBtn.itemMeta
        backMeta?.setDisplayName("§c⬅ 返回战略主控制台")
        backBtn.itemMeta = backMeta
        setButton(53, backBtn) { _, player ->
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            val menu = BaseMenuHolder(base, plugin)
            menu.setupMainMenu()
            player.openInventory(menu.inventory)
        }
    }

    private fun extractItem(player: Player, mat: Material) {
        val count = base.resourceStorage[mat] ?: 0
        if (count <= 0) return

        givePlayerItem(player, mat, count)
        base.resourceStorage[mat] = 0 // 清空
        player.playSound(player.location, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.5f)

        // 刷新界面
        val newMenu = IndustryMenuHolder(base, plugin)
        newMenu.setupMenu()
        player.openInventory(newMenu.inventory)
    }

    private fun extractAll(player: Player) {
        var hasExtracted = false
        val iterator = base.resourceStorage.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val mat = entry.key
            val count = entry.value
            if (count > 0) {
                givePlayerItem(player, mat, count)
                entry.setValue(0)
                hasExtracted = true
            }
        }

        if (hasExtracted) {
            player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 2.0f)
            val newMenu = IndustryMenuHolder(base, plugin)
            newMenu.setupMenu()
            player.openInventory(newMenu.inventory)
        } else {
            player.sendMessage("§c当前没有可以提取的物资！")
            player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
        }
    }

    private fun givePlayerItem(player: Player, mat: Material, amount: Int) {
        // 分批给予，防止超过 64 堆叠溢出丢失
        var remaining = amount
        while (remaining > 0) {
            val giveAmount = remaining.coerceAtMost(mat.maxStackSize)
            val itemStack = ItemStack(mat, giveAmount)
            // 尝试放入背包，如果满了会返回放不下的物品
            val leftOvers = player.inventory.addItem(itemStack)
            if (leftOvers.isNotEmpty()) {
                // 如果背包满了，掉落在玩家脚下
                for (left in leftOvers.values) {
                    player.world.dropItemNaturally(player.location, left)
                }
            }
            remaining -= giveAmount
        }
    }

    // 简易汉化方法
    private fun getMaterialName(mat: Material): String {
        return when(mat) {
            Material.IRON_INGOT -> "铁锭"
            Material.GOLD_INGOT -> "金锭"
            Material.DIAMOND -> "钻石"
            Material.COAL -> "煤炭"
            Material.GUNPOWDER -> "火药"
            Material.FLINT -> "燧石"
            Material.OAK_LOG -> "原木"
            Material.COBBLESTONE -> "圆石"
            else -> mat.name
        }
    }
}