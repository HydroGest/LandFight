package top.yurikale.landFight.listener

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.entity.Sheep
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.GameState
import top.yurikale.landFight.team.TeamColor
import java.util.UUID

class GameListener(private val plugin: LandFight) : Listener {

    // ================================================
    //  TNT 蓄力投掷系统
    // ================================================
    private data class TntChargeData(
        val startMs: Long,
        val bossBar: BossBar,
        val updateTask: BukkitRunnable,
        var lastRightClickTick: Int
    )

    private val playerChargeMap = mutableMapOf<UUID, TntChargeData>()
    private val MAX_TNT_SPEED = 4.0          // 最大初速度
    private val FULL_CHARGE_MS = 4000L       // 5秒蓄满
    private val RELEASE_DELAY_TICKS = 8      // 8tick无右键触发判定为松开（约0.4秒）

    private val interactCooldown = mutableMapOf<UUID, Long>()

    // ================================================
    //  工具函数
    // ================================================
    fun isSameBlockPos(loc1: org.bukkit.Location, loc2: org.bukkit.Location): Boolean {
        return loc1.world?.name == loc2.world?.name &&
                loc1.blockX == loc2.blockX &&
                loc1.blockY == loc2.blockY &&
                loc1.blockZ == loc2.blockZ
    }

    // ================================================
    //  实体伤害事件（PVP保护 + 羊据点攻击）
    // ================================================
    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val victim = event.entity

        // 【大厅 PVP 保护】
        if (plugin.stateManager.currentState == GameState.LOBBY) {
            if (damager is Player && victim is Player) {
                event.isCancelled = true
                damager.sendMessage("§c大厅禁止PVP！")
            }
            return
        }

        // 非游戏阶段不处理据点逻辑
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        if (victim !is Sheep) return
        if (damager !is Player) return
        if (damager.gameMode != GameMode.SURVIVAL) return

        // 通过被攻击羊的 UUID 找到对应的据点
        val targetBase = plugin.structurePlacer.activeBases.values
            .find { it.sheepEntityId == victim.uniqueId } ?: return

        val myTeam = plugin.teamManager.getPlayerTeam(damager) ?: TeamColor.NEUTRAL
        if (myTeam == TeamColor.NEUTRAL) {
            damager.sendMessage("§c你还没有加入任何队伍，无法占领据点！")
            return
        }

        // 队友保护：禁止攻击自家的羊
        if (targetBase.ownerTeam == myTeam) {
            event.isCancelled = true
            damager.sendMessage("§c这是你自己的据点核心，别打啦！")
            return
        }

        // ===============================================
        // TODO: 里程碑 4 预留 - 绝对防御检查口
        // if (targetBase.isProtected) {
        //     event.isCancelled = true
        //     damager.sendMessage("§c据点处于网络保护中！请先切断敌方连线！")
        //     return
        // }
        // ===============================================

        // 【斩杀判定】：如果这一击足以杀死羊
        if (victim.health - event.finalDamage <= 0) {
            // 1. 锁血（取消真实死亡事件，手动回满血）
            event.isCancelled = true
            victim.health = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: 8.0

            // 2. 战报与大本营覆灭判定
            val enemyTeam = targetBase.ownerTeam
            if (enemyTeam != TeamColor.NEUTRAL &&
                plugin.teamManager.teamsCapitals[enemyTeam]?.let { isSameBlockPos(it, targetBase.location) } == true) {
                plugin.teamManager.teamsCapitals.remove(enemyTeam)
                Bukkit.broadcastMessage(
                    "§c【重大战报】${enemyTeam?.colorCode}${enemyTeam?.displayName}" +
                            "§c 大本营被 §f${damager.name}§c 攻破！敌方全体无法复活！"
                )
            }

            // 3. 数据易主 & 图论断网
            targetBase.ownerTeam = myTeam
            plugin.structurePlacer.networkGraph.clearConnectionsOf(targetBase.id)
            Bukkit.broadcastMessage(
                "§e【据点占领】${myTeam.colorCode}${damager.name}" +
                        "§e 击破了守卫羊，成功占领据点 " +
                        "(${targetBase.location.blockX}, ${targetBase.location.blockY}, ${targetBase.location.blockZ})！"
            )

            // 4. 视觉刷新：修改羊的颜色和名字
            plugin.structurePlacer.refreshBaseVisual(
                targetBase,
                plugin.structurePlacer.isBaseCapital(targetBase.id)
            )
        }
    }

    // ================================================
    //  羊环境伤害保护
    // ================================================
    @EventHandler
    fun onSheepEnvironmentalDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is Sheep) return

        val isBaseSheep = plugin.structurePlacer.activeBases.values
            .any { it.sheepEntityId == entity.uniqueId }
        if (!isBaseSheep) return

        val base = plugin.structurePlacer.activeBases.values
            .firstOrNull { it.sheepEntityId == entity.uniqueId } ?: return

        // 非实体攻击来源（窒息、火焰、掉落、熔岩等）直接无敌
        if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
            event.cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK &&
            event.cause != EntityDamageEvent.DamageCause.PROJECTILE) {
            event.isCancelled = true
        }

        plugin.structurePlacer.refreshBaseVisual(base, plugin.structurePlacer.isBaseCapital(base.id))
    }

    // ================================================
    //  玩家PVP开关控制
    // ================================================
    @EventHandler
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val victim = event.entity

        if (!plugin.stateManager.isPvPEnabled && damager is Player && victim is Player) {
            event.isCancelled = true
            damager.sendMessage("§c当前阶段禁止互相攻击！")
        }

        if (plugin.stateManager.currentState != GameState.LOBBY) return
        if (damager is Player && victim is Player) {
            event.isCancelled = true
            damager.sendMessage("§c大厅禁止PVP！")
        }
    }

    // ================================================
    //  右键羊交互（设置大本营）
    // ================================================
    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return
        if (plugin.stateManager.currentState != GameState.IN_GAME) return

        val entity = event.rightClicked

        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL) return

        if (entity is org.bukkit.entity.Mob) {
            val guardData = plugin.guardManager.getGuardData(entity.uniqueId)
            if (guardData != null) {
                event.isCancelled = true
                val now = System.currentTimeMillis()
                if (now - (interactCooldown[player.uniqueId] ?: 0L) < 1000L) return

                val myTeam = plugin.teamManager.getPlayerTeam(player)
                if (myTeam != guardData.team) {
                    interactCooldown[player.uniqueId] = now
                    player.sendMessage("§c这是敌方守卫，无法下达指令！")
                    return
                }

                interactCooldown[player.uniqueId] = now
                val menuHolder = top.yurikale.landFight.ui.GuardMenuHolder(guardData, plugin)
                menuHolder.setupMenu()
                player.openInventory(menuHolder.inventory)
                player.playSound(player.location, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.0f, 1.1f)
                return
            }
        }

        if (entity !is Sheep) return

        val now = System.currentTimeMillis()
        if (now - (interactCooldown[player.uniqueId] ?: 0L) < 1000L) return

        val targetBase = plugin.structurePlacer.activeBases.values
            .find { it.sheepEntityId == entity.uniqueId } ?: return

        val myTeam = plugin.teamManager.getPlayerTeam(player) ?: TeamColor.NEUTRAL
        if (myTeam == TeamColor.NEUTRAL) return

        // 必须是自家的羊才能设置大本营
        if (targetBase.ownerTeam == myTeam) {
            val currentCapital = plugin.teamManager.teamsCapitals[myTeam]
            val isThisBaseCapital = currentCapital != null && isSameBlockPos(currentCapital, targetBase.location)

            if (isThisBaseCapital) {
                interactCooldown[player.uniqueId] = now
                player.sendMessage("§e提示：这个据点已经是你们队伍的大本营，无需重复设置！")
                return
            }

            interactCooldown[player.uniqueId] = now

            val oldCapitalLoc = plugin.teamManager.teamsCapitals[myTeam]
            oldCapitalLoc?.let { oldLoc ->
                val oldBase = plugin.structurePlacer.activeBases.values
                    .firstOrNull { isSameBlockPos(it.location, oldLoc) }
                oldBase?.let { plugin.structurePlacer.refreshBaseVisual(it, isCapital = false) }
            }

            // 设置新大本营
            plugin.teamManager.teamsCapitals[myTeam] = targetBase.location
            plugin.structurePlacer.refreshBaseVisual(targetBase, isCapital = true)

            player.sendMessage("§a【大本营设置成功】§f你已为本队更换新复活大本营！")
            Bukkit.broadcastMessage(
                "§e【战略转移】§f${player.name} 为 " +
                        "${myTeam.colorCode}${myTeam.displayName}§e 设立全新大本营！"
            )
        }
    }

    // ================================================
    //  守卫同队免伤保护
    // ================================================
    @EventHandler
    fun onGuardFriendlyFire(event: EntityDamageByEntityEvent) {
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        val victim = event.entity
        val damager = event.damager

        if (damager is Player && victim is org.bukkit.entity.Mob && victim !is Sheep) {
            val guardData = plugin.guardManager.getGuardData(victim.uniqueId) ?: return
            val damagerTeam = plugin.teamManager.getPlayerTeam(damager)
            if (damagerTeam == guardData.team) {
                event.isCancelled = true
            }
        }
    }

    // ================================================
    //  守卫纯净仇恨控制
    // ================================================
    @EventHandler
    fun onGuardTarget(event: org.bukkit.event.entity.EntityTargetEvent) {
        val entity = event.entity
        if (entity !is org.bukkit.entity.Mob) return

        val guardData = plugin.guardManager.getGuardData(entity.uniqueId) ?: return
        val target = event.target

        if (target is Player) {
            val targetTeam = plugin.teamManager.getPlayerTeam(target)
            if (targetTeam == guardData.team || targetTeam == TeamColor.NEUTRAL) {
                event.isCancelled = true // 取消对同队和中立玩家的仇恨
            }
        } else if (target != null) {
            event.isCancelled = true // 取消对所有非玩家生物的仇恨
        }
    }

    // ================================================
    //  方块破坏保护
    // ================================================
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val posKey = "${block.x},${block.y},${block.z}"

        // 据点原生建筑不可破坏
        if (plugin.structurePlacer.allBaseStructureBlocks.contains(posKey)) {
            event.isCancelled = true
            player.sendMessage("§c据点原生建筑不可破坏，你自己放置的方块可以拆除！")
            return
        }

        // 据点基座羊毛不可破坏
        if (Tag.WOOL.isTagged(block.type)) {
            val isBaseCore = plugin.structurePlacer.activeBases.values.any { base ->
                isSameBlockPos(base.location, block.location)
            }
            if (isBaseCore) {
                event.isCancelled = true
                player.sendMessage("§c这是据点的基座，你无法破坏它！想要占领请攻击那只羊！")
            }
        }
    }

    // ================================================
    //  玩家复活
    // ================================================
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        if (plugin.stateManager.currentState != GameState.IN_GAME) return

        val player = event.player
        val myteam = plugin.teamManager.getPlayerTeam(player)
        val capitalLoc = plugin.teamManager.teamsCapitals[myteam]

        if (capitalLoc == null) {
            player.sendMessage("§c§l[淘汰] 你们队伍当前没有大本营，死后直接变成旁观者，无法复活。")
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.gameMode = GameMode.SPECTATOR
            })
        } else {
            event.respawnLocation = capitalLoc.clone().add(0.0, 3.0, 0.0)
            player.sendMessage("§a你在队伍大本营复活了！")
            plugin.mapManager.giveMapToPlayer(player)
        }
    }

    // ================================================
    //  玩家加入
    // ================================================
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        when (plugin.stateManager.currentState) {
            GameState.LOBBY -> {
                player.gameMode = GameMode.ADVENTURE

                val lobbyWorld = Bukkit.getWorlds()[0]
                player.teleport(lobbyWorld.spawnLocation)

                player.inventory.clear()
                player.foodLevel = 20
                player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0

                for (effect in player.activePotionEffects) {
                    player.removePotionEffect(effect.type)
                }
            }

            GameState.IN_GAME -> {
                val myTeam = plugin.teamManager.getPlayerTeam(player)
                if (myTeam != TeamColor.NEUTRAL) {
                    // 已有队伍，不做额外处理
                } else {
                    val redCount = plugin.teamManager.getAliveCount(TeamColor.RED) ?: 0
                    val blueCount = plugin.teamManager.getAliveCount(TeamColor.BLUE) ?: 0

                    val targetTeam = when {
                        redCount < blueCount -> TeamColor.RED
                        blueCount < redCount -> TeamColor.BLUE
                        else -> if (Math.random() > 0.5) TeamColor.RED else TeamColor.BLUE
                    }

                    plugin.teamManager.playerTeams[player.uniqueId] = targetTeam
                    player.sendMessage("§a你中途加入战场，已分配至 ${targetTeam.colorCode}${targetTeam.displayName}§a！")
                    plugin.teamManager.setupPlayerTeam(player, targetTeam)

                    val spawnLoc = org.bukkit.Location(
                        Bukkit.getWorld(plugin.worldManager.gameWorldName),
                        0.0, 150.0, 0.0
                    )
                    val teamSpawn = plugin.teamManager.teamsCapitals[targetTeam]
                        ?.clone()?.add(0.0, 3.0, 0.0) ?: spawnLoc
                    player.teleport(teamSpawn)
                    player.gameMode = GameMode.SURVIVAL

                    player.inventory.clear()
                    player.inventory.addItem(org.bukkit.inventory.ItemStack(Material.BREAD, 16))
                    player.inventory.addItem(org.bukkit.inventory.ItemStack(Material.OAK_BOAT, 1))
                    player.inventory.addItem(org.bukkit.inventory.ItemStack(Material.COMPASS, 1))
                    plugin.mapManager.giveMapToPlayer(player)
                }
            }

            GameState.RESET -> { }
        }
    }

    // ================================================
    //  Shift + 副手 打开据点战略菜单
    // ================================================
    @EventHandler
    fun onPlayerSwapHandItems(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        if (!player.isSneaking) return

        val currentBase = plugin.structurePlacer.getBaseAtPlayer(player.location) ?: return
        val myTeam = plugin.teamManager.getPlayerTeam(player)

        if (currentBase.ownerTeam == TeamColor.NEUTRAL || currentBase.ownerTeam != myTeam) {
            event.isCancelled = true
            val ownerTeam = currentBase.ownerTeam ?: TeamColor.NEUTRAL
            player.sendMessage(
                "§c§l【访问拒绝】 §c这里是 ${ownerTeam.colorCode}${ownerTeam.displayName} " +
                        "§c的据点，你无法查看其战略菜单！"
            )
            return
        }

        event.isCancelled = true

        val menuHolder = top.yurikale.landFight.ui.BaseMenuHolder(currentBase, plugin)
        menuHolder.setupMainMenu()
        player.openInventory(menuHolder.inventory)
        player.playSound(player.location, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.0f, 1.1f)
    }

    // ================================================
    //  菜单点击事件统管
    // ================================================
    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder is top.yurikale.landFight.ui.ActionMenu) {
            holder.handleClick(event)
        }
    }

    // ================================================
    //  TNT 系统：放置即点燃
    // ================================================
    @EventHandler
    fun onPlaceTntAutoIgnite(event: BlockPlaceEvent) {
        val player = event.player
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        val block = event.block
        if (block.type != Material.TNT) return

        event.isCancelled = true
        block.type = Material.AIR

        val tnt = block.world.spawn(block.location.add(0.5, 0.0, 0.5), TNTPrimed::class.java)
        tnt.source = player
        tnt.fuseTicks = 80
        player.playSound(player.location, org.bukkit.Sound.ENTITY_TNT_PRIMED, 1f, 1f)
    }

    // ================================================
    //  TNT 系统：右键空气蓄力投掷
    // ================================================
    @EventHandler
    fun startOrRefreshTntCharge(event: PlayerInteractEvent) {
        val p = event.player
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        if (event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR) return

        val item = p.inventory.itemInMainHand
        if (item.type != Material.TNT) return

        val currentTick = Bukkit.getCurrentTick()
        val existing = playerChargeMap[p.uniqueId]

        // 已有蓄力：刷新最后右键tick，重置松开倒计时
        if (existing != null) {
            existing.lastRightClickTick = currentTick
            return
        }

        // 新建蓄力
        val bar = Bukkit.createBossBar("TNT蓄力 0.0/$MAX_TNT_SPEED", BarColor.RED, BarStyle.SOLID)
        bar.addPlayer(p)
        bar.progress = 0.0
        val chargeStart = System.currentTimeMillis()

        val task = object : BukkitRunnable() {
            override fun run() {
                val data = playerChargeMap[p.uniqueId] ?: run {
                    this.cancel()
                    return
                }
                val nowTick = Bukkit.getCurrentTick()
                // 超过设定tick数无新右键 = 松开鼠标，发射TNT
                if (nowTick - data.lastRightClickTick > RELEASE_DELAY_TICKS) {
                    fireTnt(p, data)
                    return
                }
                // 更新蓄力进度条
                val passMs = System.currentTimeMillis() - data.startMs
                val ratio = (passMs.toDouble() / FULL_CHARGE_MS).coerceAtMost(1.0)
                bar.progress = ratio
                val spdText = String.format("%.1f", ratio * MAX_TNT_SPEED)
                bar.setTitle("TNT蓄力 $spdText/$MAX_TNT_SPEED")
            }
        }
        task.runTaskTimer(plugin, 0, 1)
        playerChargeMap[p.uniqueId] = TntChargeData(chargeStart, bar, task, currentTick)
    }

    // ================================================
    //  TNT 系统：发射逻辑
    // ================================================
    private fun fireTnt(p: Player, data: TntChargeData) {
        playerChargeMap.remove(p.uniqueId)
        data.updateTask.cancel()
        data.bossBar.removeAll()

        val handItem = p.inventory.itemInMainHand
        if (handItem.type != Material.TNT) return

        // 消耗TNT
        handItem.amount -= 1
        if (handItem.amount <= 0) p.inventory.setItemInMainHand(null)

        // 计算发射速度
        val chargeTime = System.currentTimeMillis() - data.startMs
        val ratio = (chargeTime.toDouble() / FULL_CHARGE_MS).coerceAtMost(1.0)
        val speed = ratio * MAX_TNT_SPEED

        // 沿玩家视线抛出，无额外Y，自然抛物线
        val vec = p.eyeLocation.direction.normalize().multiply(speed)
        val tnt = p.world.spawn(p.eyeLocation, TNTPrimed::class.java)
        tnt.source = p
        tnt.fuseTicks = 80
        tnt.velocity = vec
        p.playSound(p.location, org.bukkit.Sound.ENTITY_TNT_PRIMED, 1f, 1f)
    }

    // ================================================
    //  TNT 系统：中断蓄力事件
    // ================================================
    @EventHandler
    fun cancelChargeOnItemSwitch(event: PlayerItemHeldEvent) {
        forceCancelCharge(event.player)
    }

    @EventHandler
    fun cancelChargeOnSwapHand(event: PlayerSwapHandItemsEvent) {
        forceCancelCharge(event.player)
    }

    @EventHandler
    fun cancelChargeOnTeleport(event: PlayerTeleportEvent) {
        forceCancelCharge(event.player)
    }

    @EventHandler
    fun cancelChargeOnDeath(event: PlayerDeathEvent) {
        forceCancelCharge(event.entity)
    }

    private fun forceCancelCharge(p: Player) {
        val data = playerChargeMap.remove(p.uniqueId) ?: return
        data.updateTask.cancel()
        data.bossBar.removeAll()
    }

    // ================================================
    //  TNT 系统：爆炸据点方块保护
    // ================================================
    @EventHandler
    fun onTntExplodeProtectBase(event: EntityExplodeEvent) {
        val tntEntity = event.entity
        if (tntEntity !is TNTPrimed) return

        val protectBlocks = mutableListOf<org.bukkit.block.Block>()
        for (block in event.blockList()) {
            val posKey = "${block.x},${block.y},${block.z}"
            // 据点原生建筑全部保护
            if (plugin.structurePlacer.allBaseStructureBlocks.contains(posKey)) {
                protectBlocks.add(block)
                continue
            }
            // 据点中心羊毛基座保护
            val isBaseCoreWool = plugin.structurePlacer.activeBases.values.any {
                isSameBlockPos(it.location, block.location)
            }
            if (isBaseCoreWool) protectBlocks.add(block)
        }
        event.blockList().removeAll(protectBlocks)
    }

    // ================================================
    //  插件关闭时清理所有蓄力资源
    // ================================================
    fun cleanAllTntCharge() {
        playerChargeMap.values.forEach { data ->
            data.updateTask.cancel()
            data.bossBar.removeAll()
        }
        playerChargeMap.clear()
    }
}