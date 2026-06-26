package top.yurikale.landFight.ui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base
import top.yurikale.landFight.team.TeamColor
import kotlin.math.floor

class IndustryMenuHolder(val base: Base, private val plugin: LandFight) : ActionMenu(54, "§0⚒ 据点工业系统") {
    fun setupMenu() {
        this.inventory.clear()
        // ================= 背景与装饰 =================
        val bgDark = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            val m = itemMeta; m?.setDisplayName(" "); itemMeta = m
        }
        fillBackground(bgDark)
        // ================= 第二排：10秒周期进度条 (Slot 9-17) =================
        renderProgressBar()
        // ================= 顶部：地质扫描报告 (Slot 0-8) =================
        val scanRadar = ItemStack(Material.COMPASS)
        val radarMeta = scanRadar.itemMeta
        radarMeta?.setDisplayName("§b§l🌐 地质勘测报告")
        val radarLore = mutableListOf(
            "§7当前环境已探明以下资源分布：",
            "§8§m-------------------------",
            "§a【工程算法公示】",
            "§7本界面的产出比重，是根据据点周边",
            "§e30格半径§7内扫描到的物理方块绝对数量",
            "§7通过 §elog2(x+2)§7 对数算法计算得出的。",
            "§8§m-------------------------"
        )
        if (!base.isScanned || base.resourceWeights.isEmpty()) {
            radarLore.add("§c未扫描或该地区极度贫瘠，无法产出。")
        } else {
            base.resourceWeights.entries.sortedByDescending { it.value }.forEach { (mat, weight) ->
                val percentStr = String.format("%.2f%%", weight * 100)
                radarLore.add("§7▶ §f${getMaterialName(mat)}: §e$percentStr")
            }
        }
        radarLore.add("§8§m-------------------------")
        radarLore.add("§7* 剩余产出极大概率为无用的岩层碎屑。")
        radarMeta?.lore = radarLore
        scanRadar.itemMeta = radarMeta
        setButton(4, scanRadar)
        // ================= 中部：工业存储库 =================
        val storageSlots = listOf(
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        )
        var slotPointer = 0
        val isCapital = plugin.structurePlacer.isBaseCapital(base.id)
        // 【修复】强行校验物流连通性，断网自动关闭物流模式
        if (!isCapital && base.isLogisticsEnabled) {
            val owner = base.ownerTeam
            val capitalLoc = if (owner != null && owner != TeamColor.NEUTRAL) plugin.teamManager.teamsCapitals[owner] else null
            val capitalBase = capitalLoc?.let { loc ->
                plugin.structurePlacer.activeBases.values.find { plugin.stateManager.isSameBlockPos(it.location, loc) }
            }
            if (capitalBase == null || !plugin.structurePlacer.networkGraph.isConnected(base.id, capitalBase.id)) {
                base.isLogisticsEnabled = false
            }
        }
        val autoLogisticsOn = base.isLogisticsEnabled
        // 普通据点开启物流：不展示库存
        if (!isCapital && autoLogisticsOn) {
            val emptyStorage = ItemStack(Material.MINECART)
            val em = emptyStorage.itemMeta
            em?.setDisplayName("§7[自动物流转运中，本地无库存]")
            em?.lore = listOf("§7所有产出会自动发往大本营，本地不留存物资")
            emptyStorage.itemMeta = em
            setButton(22, emptyStorage)
        } else if (base.resourceStorage.isEmpty() || base.resourceStorage.values.all { it == 0 }) {
            val emptyStorage = ItemStack(Material.MINECART)
            val em = emptyStorage.itemMeta
            val title = if (isCapital) "§7[大本营总仓为空]" else "§7[据点仓储为空]"
            em?.setDisplayName(title)
            em?.lore = listOf("§7工业机器正在努力运转中...", "§7(也可能是据点处于中立停工状态)")
            emptyStorage.itemMeta = em
            setButton(22, emptyStorage)
        } else {
            base.resourceStorage.filter { it.value > 0 }.forEach { (mat, count) ->
                if (slotPointer >= storageSlots.size) return@forEach
                val item = ItemStack(mat)
                val meta = item.itemMeta
                meta?.setDisplayName("§a§l${getMaterialName(mat)}")
                val loreLines = mutableListOf(
                    "§7当前库存合计: §f$count §7/ 128 个",
                    "§7产出进度(累加器): §e${String.format("%.1f%%", (base.resourceAccumulator[mat] ?: 0.0) * 100)}",
                    "§8§m-------------------------"
                )
                when {
                    isCapital -> {
                        val sourceLines = mutableListOf<String>()
                        var externalTotal = 0
                        val targetSuffix = "_${mat.name}"
                        base.resourceSourceMark.forEach { (key, num) ->
                            if (key.startsWith("source_") && key.endsWith(targetSuffix)) {
                                val parts = key.split("_")
                                val sourceBaseId = parts.getOrNull(1) ?: return@forEach
                                if (sourceBaseId != base.id.toString()) {
                                    sourceLines.add("§7- 据点#$sourceBaseId 输送: §f$num")
                                    externalTotal += num
                                }
                            }
                        }
                        val selfProduced = (count - externalTotal).coerceAtLeast(0)
                        val totalVerified = selfProduced + externalTotal
                        loreLines.add("§d物资来源明细：")
                        loreLines.add("§7- 本据点自产: §f$selfProduced")
                        if (sourceLines.isNotEmpty()) {
                            loreLines.addAll(sourceLines)
                        }
                        loreLines.add("§8§m-------------------------")
                        loreLines.add("§d来源总和校验: §f$totalVerified §7(提取以此为准)")
                        loreLines.add("§e▶ 点击提取 §a64 §e个 (不足则全取)") // 【修复】提示限制64个
                        meta?.lore = loreLines
                        item.itemMeta = meta
                        setButton(storageSlots[slotPointer], item) { _, player -> extractItem(player, mat) }
                    }
                    !autoLogisticsOn -> {
                        loreLines.add("§e▶ 点击提取 §a64 §e个 (不足则全取)") // 【修复】提示限制64个
                        meta?.lore = loreLines
                        item.itemMeta = meta
                        setButton(storageSlots[slotPointer], item) { _, player -> extractItem(player, mat) }
                    }
                }
                slotPointer++
            }
        }
        // ================= 底部：操作台 (Slot 45-53) =================
        val coreInfo = ItemStack(Material.BLAST_FURNACE)
        val coreMeta = coreInfo.itemMeta
        coreMeta?.setDisplayName("§e§l🏭 工业产能引擎")
        val outCap = when(base.level) { 1->10; 2->20; 3->40; else->0 }
        coreMeta?.lore = listOf(
            "§7据点工业等级: §bLv.${base.level} §7(请在“城防管理中心”升级)",
            "§7总吞吐功率: §a$outCap 方块/10秒",
            "§8§m-------------------------",
            "§7产能说明：每10秒系统会执行 ${outCap} 次挖掘",
            "§7每次挖掘有几率获得上方雷达显示的物资。"
        )
        coreInfo.itemMeta = coreMeta
        setButton(45, coreInfo)
        if (isCapital) {
            val extractAllBtn = ItemStack(Material.HOPPER)
            val exMeta = extractAllBtn.itemMeta
            exMeta?.setDisplayName("§6§l📦 提取总库物资 (发配至背包)")
            // 【修复】提示限制每种64个
            exMeta?.lore = listOf("§7将大本营仓储内的物资提取到您的背包中", "§7(§e每种物资最多提取 64 个§7)")
            extractAllBtn.itemMeta = exMeta
            setButton(49, extractAllBtn) { _, player -> extractAll(player) }
        } else {
            val transportBtn = ItemStack(if (autoLogisticsOn) Material.HOPPER_MINECART else Material.MINECART)
            val trMeta = transportBtn.itemMeta
            trMeta?.setDisplayName(if (autoLogisticsOn) "§a§l🚚 物流专线已激活" else "§6§l🚛 建立物资回传物流")
            trMeta?.lore = if (autoLogisticsOn) {
                listOf(
                    "§7当前状态: §a自动托管中",
                    "§7每周期自动转运全部库存至大本营(运损10%)",
                    "§8§m-------------------------",
                    "§e左键关闭自动物流 | §c右键无库存可手动发车"
                )
            } else {
                listOf(
                    "§7当前状态: §c本地仓储留存",
                    "§7左键开启自动物流 → 立刻转运现有全部库存",
                    "§c[!] 物流依赖据点连通性 | 转运运损10%",
                    "§8§m-------------------------",
                    "§e右键：立即发车现有库存送往大本营",
                    "§a未开启物流时，可直接点击上方物资提取"
                )
            }
            transportBtn.itemMeta = trMeta
            setButton(49, transportBtn) { clickEvent, player ->
                if (clickEvent.isRightClick) {
                    sendToCapital(player)
                } else {
                    val willOpen = !base.isLogisticsEnabled
                    if (willOpen) {
                        // 【修复】只有连通且成功发车(或无库存但连通)才开启
                        val success = sendToCapital(player)
                        base.isLogisticsEnabled = success
                    } else {
                        base.isLogisticsEnabled = false
                    }
                    setupMenu()
                }
            }
        }
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
    private fun renderProgressBar() {
        val cycleMillis = 10000L
        val elapsed = System.currentTimeMillis() - plugin.industryManager.lastCycleStartTime
        val progress = ((elapsed % cycleMillis).toDouble() / cycleMillis).coerceIn(0.0, 1.0)
        val filledCount = floor(progress * 9).toInt().coerceIn(0, 9)
        val greenPane = ItemStack(Material.GREEN_STAINED_GLASS_PANE).apply {
            val m = itemMeta; m?.setDisplayName("§a§l工业流水线运行中..."); itemMeta = m
        }
        val bluePane = ItemStack(Material.BLUE_STAINED_GLASS_PANE).apply {
            val m = itemMeta; m?.setDisplayName("§9§l下一周期待产出"); itemMeta = m
        }
        val progressText = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            val m = itemMeta
            m?.setDisplayName("§b⏱ 工业周期进度")
            m?.lore = listOf(
                "§7当前进度: §a${String.format("%.1f%%", progress * 100)}",
                "§7周期: §f10秒 / 轮",
                "§8§m-------------------------",
                "§7绿色 = 已完成 | 蓝色 = 待完成"
            )
            itemMeta = m
        }
        for (i in 9..17) {
            val relativeIndex = i - 9
            when {
                i == 13 -> setButton(i, progressText)
                relativeIndex < filledCount -> setButton(i, greenPane)
                else -> setButton(i, bluePane)
            }
        }
    }
    /** 手动发车送物资到大本营，返回是否成功连通(用于判断是否可开启自动物流) */
    private fun sendToCapital(player: Player): Boolean {
        val owner = base.ownerTeam ?: return false
        val capitalLoc = plugin.teamManager.teamsCapitals[owner] ?: return false
        val capitalBase = plugin.structurePlacer.activeBases.values.find {
            plugin.stateManager.isSameBlockPos(it.location, capitalLoc)
        } ?: run {
            player.sendMessage("§c大本营据点数据异常！")
            return false
        }
        val isConnected = plugin.structurePlacer.networkGraph.isConnected(base.id, capitalBase.id)
        if (!isConnected) {
            player.sendMessage("§c§l[物流受阻] §f据点与大本营交通线断开，无法发车！")
            player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return false
        }
        var hasTransferred = false
        val storageCopy = base.resourceStorage.toMap()
        storageCopy.forEach { (mat, count) ->
            if (count <= 0) return@forEach
            val loss = floor(count * 0.1).toInt()
            val finalAmount = count - loss
            if (finalAmount <= 0) return@forEach

            val sourceKey = "source_${base.id}_${mat.name}"
            capitalBase.resourceSourceMark[sourceKey] =
                capitalBase.resourceSourceMark.getOrDefault(sourceKey, 0) + finalAmount

            val currentCapStorage = capitalBase.resourceStorage.getOrDefault(mat, 0)
            capitalBase.resourceStorage[mat] = (currentCapStorage + finalAmount).coerceAtMost(128)

            hasTransferred = true
        }
        base.resourceStorage.clear()
        base.resourceAccumulator.clear()
        if (hasTransferred) {
            player.sendMessage("§a§l[物流成功] §f物资送达大本营，扣除10%运损，本地库存清空！")
            player.playSound(player.location, org.bukkit.Sound.ENTITY_MINECART_RIDING, 1.0f, 1.0f)
            setupMenu()
        } else {
            player.sendMessage("§c当前据点无库存可发车！")
            player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
        }
        return true // 只要连通了就算成功
    }
    private fun getVerifiedAmount(mat: Material): Int {
        val targetSuffix = "_${mat.name}"
        var externalTotal = 0
        base.resourceSourceMark.forEach { (key, num) ->
            if (key.startsWith("source_") && key.endsWith(targetSuffix)) {
                val parts = key.split("_")
                val sourceBaseId = parts.getOrNull(1) ?: return@forEach
                if (sourceBaseId != base.id.toString()) {
                    externalTotal += num
                }
            }
        }
        val storageCount = base.resourceStorage[mat] ?: 0
        val selfProduced = (storageCount - externalTotal).coerceAtLeast(0)
        return selfProduced + externalTotal
    }
    private fun clearSourceMarks(mat: Material) {
        val targetSuffix = "_${mat.name}"
        val keysToRemove = base.resourceSourceMark.keys.filter {
            it.startsWith("source_") && it.endsWith(targetSuffix)
        }
        keysToRemove.forEach { base.resourceSourceMark.remove(it) }
    }
    /** 提取单种物资 */
    private fun extractItem(player: Player, mat: Material) {
        val isCapital = plugin.structurePlacer.isBaseCapital(base.id)
        val amount = if (isCapital) getVerifiedAmount(mat) else (base.resourceStorage[mat] ?: 0)
        if (amount <= 0) return
        // 【修复】最多只提取 64 个
        val amountToTake = minOf(64, amount)
        givePlayerItem(player, mat, amountToTake)
        val newCount = (base.resourceStorage[mat] ?: 0) - amountToTake
        base.resourceStorage[mat] = newCount.coerceAtLeast(0)
        // 如果该物资被取空，清除来源标记；如果有剩余，保留标记让自产自然扣减
        if (newCount <= 0 && isCapital) {
            clearSourceMarks(mat)
        }
        player.playSound(player.location, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.5f)
        setupMenu()
    }
    /** 大本营一键提取全部 */
    private fun extractAll(player: Player) {
        val isCapital = plugin.structurePlacer.isBaseCapital(base.id)
        var hasExtracted = false
        val materials = base.resourceStorage.keys.toList()
        for (mat in materials) {
            val amount = if (isCapital) getVerifiedAmount(mat) else (base.resourceStorage[mat] ?: 0)
            if (amount > 0) {
                // 【修复】最多只提取 64 个
                val amountToTake = minOf(64, amount)
                givePlayerItem(player, mat, amountToTake)
                val newCount = (base.resourceStorage[mat] ?: 0) - amountToTake
                base.resourceStorage[mat] = newCount.coerceAtLeast(0)
                if (newCount <= 0 && isCapital) {
                    clearSourceMarks(mat)
                }
                hasExtracted = true
            }
        }
        if (hasExtracted) {
            player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 2.0f)
            setupMenu()
        } else {
            player.sendMessage("§c当前没有可以提取的物资！")
            player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
        }
    }
    private fun givePlayerItem(player: Player, mat: Material, amount: Int) {
        var remaining = amount
        while (remaining > 0) {
            val giveAmount = remaining.coerceAtMost(mat.maxStackSize)
            val itemStack = ItemStack(mat, giveAmount)
            val leftOvers = player.inventory.addItem(itemStack)
            leftOvers.values.forEach { player.world.dropItemNaturally(player.location, it) }
            remaining -= giveAmount
        }
    }
    private fun getMaterialName(mat: Material): String {
        return when (mat) {
            Material.IRON_INGOT -> "铁锭"
            Material.GOLD_INGOT -> "金锭"
            Material.COPPER_INGOT -> "铜锭"
            Material.DIAMOND -> "钻石"
            Material.COAL -> "煤炭"
            Material.GUNPOWDER -> "火药"
            Material.FLINT -> "燧石"
            Material.OAK_LOG -> "原木"
            Material.COBBLESTONE -> "圆石"
            Material.SAND -> "沙子"
            Material.FEATHER -> "鸡毛"
            else -> mat.name
        }
    }
}