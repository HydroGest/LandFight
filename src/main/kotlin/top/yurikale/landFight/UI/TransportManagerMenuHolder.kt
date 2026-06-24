package top.yurikale.landFight.ui

import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base
import top.yurikale.landFight.team.TeamColor

enum class ManageState { IDLE, ADDING, REMOVING }

class TransportManageMenuHolder(
    val base: Base,
    private val plugin: LandFight,
    private val state: ManageState = ManageState.IDLE,
    private val selectedBases: Set<Int> = setOf()
) : ActionMenu(54, "§0🛤 交通线管理") {

    fun setupMenu() {
        val graph = plugin.structurePlacer.networkGraph
        val myTeam = base.ownerTeam ?: TeamColor.NEUTRAL
        val neighbors = graph.getNeighbors(base.id)

        val gridMap = mutableMapOf<Int, Base>()
        plugin.structurePlacer.activeBases.values.forEach { b ->
            val col = (b.location.blockX + 750) / 250
            val row = (b.location.blockZ + 750) / 250
            if (col in 0..5 && row in 0..5) {
                gridMap[row * 9 + col] = b
            }
        }

        val myCol = (base.location.blockX + 750) / 250
        val myRow = (base.location.blockZ + 750) / 250

        // 渲染左侧 6x6 地图区域
        for (row in 0..5) {
            for (col in 0..5) {
                val slot = row * 9 + col
                val targetBase = gridMap[slot]

                if (targetBase == null) {
                    val emptyBg = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
                        val m = itemMeta; m?.setDisplayName("§8[未探测的荒野]"); itemMeta = m
                    }
                    setButton(slot, emptyBg)
                    continue
                }

                val isSelf = targetBase.id == base.id
                val isCapital = plugin.structurePlacer.isBaseCapital(targetBase.id)
                val isConnected = neighbors.contains(targetBase.id)
                val isSelected = selectedBases.contains(targetBase.id)
                val isEnemyOrNeutral = targetBase.ownerTeam != myTeam

                var item: ItemStack
                var canClick = false
                var clickAction: (() -> Unit)? = null

                // 核心：构建详细的保姆级 Lore
                val lore = mutableListOf<String>()
                lore.add("§7据点坐标: §f[${targetBase.location.blockX}, ${targetBase.location.blockZ}]")
                lore.add("§7网格位置: §f第 ${col + 1} 列, 第 ${row + 1} 行")
                lore.add("§8§m-------------------------")

                if (state == ManageState.ADDING) {
                    when {
                        isSelf -> {
                            item = getBaseItem(targetBase, Material.TINTED_GLASS)
                            lore.add("§c▶ 这是你当前所在的据点")
                        }
                        isEnemyOrNeutral -> {
                            item = ItemStack(Material.BARRIER).apply {
                                val m = itemMeta; m?.setDisplayName("§c无法连接：非己方据点"); itemMeta = m
                            }
                            lore.add("§c▶ 只能将交通线连接至己方控制的据点！")
                        }
                        isConnected -> {
                            item = getBaseItem(targetBase, getWool(targetBase.ownerTeam))
                            item.addGlint()
                            lore.add("§a[✔] 已与当前据点直连")
                            lore.add("§7(无需重复建设)")
                        }
                        else -> { // 未直连的己方据点
                            item = getBaseItem(targetBase, getGlass(targetBase.ownerTeam))

                            // 计算造价并向玩家完全透明展示
                            val dCol = Math.abs(myCol - col)
                            val dRow = Math.abs(myRow - row)
                            val distSq = dCol * dCol + dRow * dRow
                            val nodeCost = distSq * 10

                            lore.add("§e[建设评估]")
                            lore.add("§7距离参数: §fΔx=$dCol, Δy=$dRow")
                            lore.add("§7造价公式: §f10 × ($dCol² + $dRow²)")
                            lore.add("§7单条路线花费: §6$nodeCost 圆石")
                            lore.add("§8§m-------------------------")

                            if (isSelected) {
                                item.addGlint()
                                lore.add("§a✅ 已加入建设清单")
                                lore.add("§c▶ 点击取消选中")
                            } else {
                                lore.add("§e▶ 点击选中以建立交通线")
                            }
                            canClick = true
                            clickAction = { toggleSelection(targetBase.id) }
                        }
                    }
                } else if (state == ManageState.REMOVING) {
                    when {
                        isSelf -> {
                            item = getBaseItem(targetBase, Material.TINTED_GLASS)
                            lore.add("§c▶ 这是你当前所在的据点")
                        }
                        isConnected -> {
                            item = getBaseItem(targetBase, getWool(targetBase.ownerTeam))
                            if (isSelected) {
                                item.addGlint()
                                lore.add("§c🧨 已标记为【待拆除】")
                                lore.add("§a▶ 点击取消标记")
                            } else {
                                lore.add("§a[✔] 当前直连中")
                                lore.add("§e▶ 点击选中以切断该连线")
                            }
                            canClick = true
                            clickAction = { toggleSelection(targetBase.id) }
                        }
                        else -> {
                            item = ItemStack(Material.BARRIER).apply {
                                val m = itemMeta; m?.setDisplayName("§c无法操作：未连接"); itemMeta = m
                            }
                            lore.add("§c▶ 该据点未与当前据点直接相连")
                        }
                    }
                } else {
                    // IDLE 默认状态渲染
                    val mat = when {
                        isSelf -> getGlass(targetBase.ownerTeam)
                        isCapital -> getBanner(targetBase.ownerTeam)
                        else -> getWool(targetBase.ownerTeam)
                    }
                    item = getBaseItem(targetBase, mat)

                    if (isSelf) {
                        lore.add("§a▶ 你当前所在的指挥枢纽")
                    } else if (isConnected) {
                        item.addGlint()
                        lore.add("§a[🔗 直连] 与当前据点有物理连线")
                    } else {
                        lore.add("§c[✖ 断开] 未与当前据点直连")
                    }
                    lore.add("§8§m-------------------------")
                    lore.add("§e请点击右侧 [+] 或 [-] 按钮开始操作")
                }

                val meta = item.itemMeta
                meta?.lore = lore
                item.itemMeta = meta

                if (canClick) {
                    setButton(slot, item) { _, player ->
                        player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.5f)
                        clickAction?.invoke()
                    }
                } else {
                    setButton(slot, item)
                }
            }
        }

        // 渲染地图右侧分割线
        val border = ItemStack(Material.YELLOW_STAINED_GLASS_PANE).apply {
            val meta = itemMeta; meta?.setDisplayName("§e[控制面板边界]"); itemMeta = meta
        }
        for (row in 0..5) setButton(row * 9 + 6, border)

        // 渲染右侧操作按钮面板
        when (state) {
            ManageState.IDLE -> {
                val addBtn = ItemStack(Material.DIAMOND_PICKAXE)
                val addMeta = addBtn.itemMeta
                addMeta?.setDisplayName("§a§l[+] 建立新交通线")
                addMeta?.lore = listOf(
                    "§7点击进入建设模式，在左侧地图",
                    "§7勾选想要直连的己方据点。",
                    "§8§m-------------------------",
                    "§6[计费说明]",
                    "§7费用与距离的平方成正比！",
                    "§7公式: §f10 × (Δx² + Δy²) 圆石",
                    "§7距离越远，开销成倍增加！"
                )
                addBtn.itemMeta = addMeta
                setButton(25, addBtn) { _, player ->
                    player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
                    reopenMenu(player, ManageState.ADDING, setOf())
                }

                val removeBtn = ItemStack(Material.TNT)
                val remMeta = removeBtn.itemMeta
                remMeta?.setDisplayName("§c§l[-] 拆除已有交通线")
                remMeta?.lore = listOf("§7点击进入拆除模式，可主动切断", "§7当前据点已有的直连边。")
                removeBtn.itemMeta = remMeta
                setButton(34, removeBtn) { _, player ->
                    player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
                    reopenMenu(player, ManageState.REMOVING, setOf())
                }
            }
            ManageState.ADDING -> {
                val cost = calculateCost()
                val confirmBtn = ItemStack(Material.EMERALD_BLOCK)
                val conMeta = confirmBtn.itemMeta
                conMeta?.setDisplayName("§a§l✔ 确认支付并连接")
                conMeta?.lore = listOf(
                    "§7已选据点数: §f${selectedBases.size}",
                    "§7工程总花费: §6$cost 圆石",
                    "§8§m-------------------------",
                    if(selectedBases.isEmpty()) "§c请先在左侧地图点击选择！" else "§e▶ 点击扣除物资并执行工程"
                )
                confirmBtn.itemMeta = conMeta
                setButton(25, confirmBtn) { _, player -> executeAdd(player, cost) }

                val cancelBtn = ItemStack(Material.REDSTONE_BLOCK)
                val canMeta = cancelBtn.itemMeta
                canMeta?.setDisplayName("§c§l✖ 取消建设")
                cancelBtn.itemMeta = canMeta
                setButton(34, cancelBtn) { _, player -> reopenMenu(player, ManageState.IDLE, setOf()) }
            }
            ManageState.REMOVING -> {
                val confirmBtn = ItemStack(Material.REDSTONE_BLOCK)
                val conMeta = confirmBtn.itemMeta
                conMeta?.setDisplayName("§c§l✔ 确认截断网络")
                conMeta?.lore = listOf(
                    "§7已选拆除数: §f${selectedBases.size}",
                    "§8§m-------------------------",
                    if(selectedBases.isEmpty()) "§c请先在左侧地图点击选择！" else "§e▶ 点击无情截断"
                )
                confirmBtn.itemMeta = conMeta
                setButton(25, confirmBtn) { _, player -> executeRemove(player) }

                val cancelBtn = ItemStack(Material.SLIME_BLOCK)
                val canMeta = cancelBtn.itemMeta
                canMeta?.setDisplayName("§a§l✖ 取消拆除")
                cancelBtn.itemMeta = canMeta
                setButton(34, cancelBtn) { _, player -> reopenMenu(player, ManageState.IDLE, setOf()) }
            }
        }

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

    private fun toggleSelection(id: Int) {
        val newSelection = selectedBases.toMutableSet()
        if (!newSelection.remove(id)) newSelection.add(id)
        val viewer = inventory.viewers.firstOrNull() as? Player ?: return
        reopenMenu(viewer, state, newSelection)
    }

    private fun reopenMenu(player: Player, newState: ManageState, newSelection: Set<Int>) {
        val newMenu = TransportManageMenuHolder(base, plugin, newState, newSelection)
        newMenu.setupMenu()
        player.openInventory(newMenu.inventory)
    }

    private fun calculateCost(): Int {
        var totalCost = 0
        val c1 = (base.location.blockX + 750) / 250
        val r1 = (base.location.blockZ + 750) / 250
        selectedBases.forEach { id ->
            val b = plugin.structurePlacer.activeBases[id] ?: return@forEach
            val c2 = (b.location.blockX + 750) / 250
            val r2 = (b.location.blockZ + 750) / 250
            totalCost += 10 * ((c1 - c2) * (c1 - c2) + (r1 - r2) * (r1 - r2))
        }
        return totalCost
    }

    private fun executeAdd(player: Player, cost: Int) {
        if (selectedBases.isEmpty()) return
        if (!consumeCobblestone(player, cost)) {
            player.sendMessage("§c物资不足！你需要 $cost 个圆石(Cobblestone) 作为筑路材料。")
            player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // 1. 确定操作者的队伍
        val playerTeam = plugin.teamManager.getPlayerTeam(player)
        val targetIdsStr = selectedBases.joinToString(", ") { "#$it" }

        // 2. 执行网络加边逻辑
        selectedBases.forEach { plugin.structurePlacer.networkGraph.addConnection(base.id, it) }
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)

        // 3. 【精准全队广播】
        if (playerTeam != TeamColor.NEUTRAL) {
            val broadcastMsg = "§a【基建快讯】 队友 §f${player.name} §a为据点 §e#${base.id} §a铺设了通往 §e$targetIdsStr §a的交通网络！"

            org.bukkit.Bukkit.getOnlinePlayers().forEach { p ->
                // 只发送给同一阵营的在线队友
                if (plugin.teamManager.getPlayerTeam(p) == playerTeam) {
                    p.sendMessage(broadcastMsg)
                    p.playSound(p.location, org.bukkit.Sound.BLOCK_ANVIL_USE, 0.6f, 1.5f)
                }
            }
        } else {
            // 后备方案：如果没有队伍（比如管理员测试），仅提示个人
            player.sendMessage("§a【基建工程】 交通线铺设完毕！")
        }

        reopenMenu(player, ManageState.IDLE, setOf())
    }

    private fun executeRemove(player: Player) {
        if (selectedBases.isEmpty()) return

        // 1. 确定操作者的队伍
        val playerTeam = plugin.teamManager.getPlayerTeam(player)
        val targetIdsStr = selectedBases.joinToString(", ") { "#$it" }

        // 2. 执行网络删边逻辑
        selectedBases.forEach { plugin.structurePlacer.networkGraph.removeConnection(base.id, it) }
        player.playSound(player.location, org.bukkit.Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 1.0f)

        // 3. 【精准全队战术预警】
        if (playerTeam != TeamColor.NEUTRAL) {
            val alertMsg = "§c【战术预警】 队友 §f${player.name} §c切断了据点 §e#${base.id} §c与 §e$targetIdsStr §c的物理连线！"

            org.bukkit.Bukkit.getOnlinePlayers().forEach { p ->
                // 只发送给同一阵营的在线队友
                if (plugin.teamManager.getPlayerTeam(p) == playerTeam) {
                    p.sendMessage(alertMsg)
                    p.playSound(p.location, org.bukkit.Sound.ENTITY_BLAZE_DEATH, 0.6f, 1.2f)
                }
            }
        } else {
            // 后备方案
            player.sendMessage("§e【战术指令】 已截断所选据点的物理连线！")
        }

        reopenMenu(player, ManageState.IDLE, setOf())
    }

    private fun consumeCobblestone(player: Player, amount: Int): Boolean {
        if (amount == 0) return true
        var count = 0
        player.inventory.contents.forEach { if (it?.type == Material.COBBLESTONE) count += it.amount }
        if (count < amount) return false

        var remaining = amount
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (item.type == Material.COBBLESTONE) {
                if (item.amount > remaining) {
                    item.amount -= remaining; break
                } else {
                    remaining -= item.amount; player.inventory.setItem(i, null)
                }
            }
        }
        return true
    }

    private fun getWool(team: TeamColor?) = when (team) { TeamColor.RED -> Material.RED_WOOL; TeamColor.BLUE -> Material.BLUE_WOOL; else -> Material.GRAY_WOOL }
    private fun getGlass(team: TeamColor?) = when (team) { TeamColor.RED -> Material.RED_STAINED_GLASS; TeamColor.BLUE -> Material.BLUE_STAINED_GLASS; else -> Material.GRAY_STAINED_GLASS }
    private fun getBanner(team: TeamColor?) = when (team) { TeamColor.RED -> Material.RED_BANNER; TeamColor.BLUE -> Material.BLUE_BANNER; else -> Material.GRAY_BANNER }
    private fun ItemStack.addGlint() {
        val m = itemMeta; m?.addEnchant(Enchantment.POWER, 1, true); m?.addItemFlags(ItemFlag.HIDE_ENCHANTS); itemMeta = m
    }
    private fun getBaseItem(b: Base, mat: Material): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta?.setDisplayName("${b.ownerTeam?.colorCode}据点 #${b.id}")
        item.itemMeta = meta
        return item
    }
}