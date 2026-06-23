package top.yurikale.landFight.listener

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.entity.Sheep
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import top.yurikale.landFight.state.GameState
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerRespawnEvent
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.team.TeamColor

class GameListener(private val plugin: LandFight) : Listener {
    // 安全的方块坐标对比
    fun isSameBlockPos(loc1: org.bukkit.Location, loc2: org.bukkit.Location): Boolean {
        return loc1.world?.name == loc2.world?.name &&
                loc1.blockX == loc2.blockX &&
                loc1.blockY == loc2.blockY &&
                loc1.blockZ == loc2.blockZ
    }

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

        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        if (victim !is Sheep) return
        if (damager !is Player) return
        if (damager.gameMode != GameMode.SURVIVAL) return

        // 通过被攻击羊的 UUID 找到对应的据点
        val targetBase = plugin.structurePlacer.activeBases.values.find { it.sheepEntityId == victim.uniqueId } ?: return
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
        // TODO: 这里是给里程碑 4 预留的 绝对防御 检查口
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
            if (enemyTeam != TeamColor.NEUTRAL && plugin.teamManager.teamsCapitals[enemyTeam]?.let { isSameBlockPos(it, targetBase.location) } == true) {
                plugin.teamManager.teamsCapitals.remove(enemyTeam)
                org.bukkit.Bukkit.broadcastMessage("§c【重大战报】${enemyTeam?.colorCode}${enemyTeam?.displayName}§c 大本营被 §f${damager.name}§c 攻破！敌方全体无法复活！")
            }

            // 3. 数据易主 & 图论断网
            targetBase.ownerTeam = myTeam
            plugin.structurePlacer.networkGraph.clearConnectionsOf(targetBase.id)
            org.bukkit.Bukkit.broadcastMessage("§e【据点占领】${myTeam.colorCode}${damager.name}§e 击破了守卫羊，成功占领据点 (${targetBase.location.blockX}, ${targetBase.location.blockY}, ${targetBase.location.blockZ})！")

            // 4. 视觉刷新：修改羊的颜色和名字
            plugin.structurePlacer.refreshBaseVisual(targetBase, plugin.structurePlacer.isBaseCapital(targetBase.id))
        }
    }

    @EventHandler
    fun onSheepEnvironmentalDamage(event: org.bukkit.event.entity.EntityDamageEvent) {
        val entity = event.entity
        if (entity !is org.bukkit.entity.Sheep) return

        // 检查这只羊是不是据点核心
        val isBaseSheep = plugin.structurePlacer.activeBases.values.any { it.sheepEntityId == entity.uniqueId }
        if (!isBaseSheep) return

        val base = plugin.structurePlacer.activeBases.values.firstOrNull { it.sheepEntityId == entity.uniqueId } ?: return

        // 如果伤害来源不是其他实体（比如窒息、火焰、掉落、熔岩），直接无敌
        if (event.cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
            event.cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK &&
            event.cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.PROJECTILE) {

            event.isCancelled = true
        }

        plugin.structurePlacer.refreshBaseVisual(base, plugin.structurePlacer.isBaseCapital(base.id))

    }

    private val interactCooldown = mutableMapOf<java.util.UUID, Long>()

    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        val entity = event.rightClicked
        if (entity !is Sheep) return

        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL) return

        val now = System.currentTimeMillis()
        if (now - (interactCooldown[player.uniqueId] ?: 0L) < 1000L) {
            return
        }

        val targetBase = plugin.structurePlacer.activeBases.values.find { it.sheepEntityId == entity.uniqueId } ?: return
        val myTeam = plugin.teamManager.getPlayerTeam(player) ?: TeamColor.NEUTRAL
        if (myTeam == TeamColor.NEUTRAL) return

        // 必须是自家的羊
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
            // 如果已有旧大本营，先把旧据点改回普通据点样式
            oldCapitalLoc?.let { oldLoc ->
                val oldBase = plugin.structurePlacer.activeBases.values
                    .firstOrNull { plugin.stateManager.isSameBlockPos(it.location, oldLoc) }
                oldBase?.let { plugin.structurePlacer.refreshBaseVisual(it, isCapital = false) }
            }

            // 设置新大本营
            plugin.teamManager.teamsCapitals[myTeam] = targetBase.location
            // 刷新新据点为大本营样式
            plugin.structurePlacer.refreshBaseVisual(targetBase, isCapital = true)

            player.sendMessage("§a【大本营设置成功】§f你已为本队更换新复活大本营！")
            org.bukkit.Bukkit.broadcastMessage("§e【战略转移】§f${player.name} 为 ${myTeam.colorCode}${myTeam.displayName}§e 设立全新大本营！")
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val posKey = "${block.x},${block.y},${block.z}"

        if (plugin.structurePlacer.allBaseStructureBlocks.contains(posKey)) {
            event.isCancelled = true
            player.sendMessage("§c据点原生建筑不可破坏，你自己放置的方块可以拆除！")
            return
        }

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

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        if (plugin.stateManager.currentState != GameState.IN_GAME) return

        val player = event.player
        val myteam = plugin.teamManager.getPlayerTeam(player)
        val capitalLoc = plugin.teamManager.teamsCapitals[myteam]

        if (capitalLoc == null) {
            player.sendMessage("§c§l[淘汰] 你们队伍当前没有大本营，死后直接变成旁观者，无法复活。")
            org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                player.gameMode = GameMode.SPECTATOR
            })
        } else {
            event.respawnLocation = capitalLoc.clone().add(0.0, 3.0, 0.0)
            player.sendMessage("§a你在队伍大本营复活了！")
            plugin.mapManager.giveMapToPlayer(player)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        val player = event.player

        when (plugin.stateManager.currentState) {
            GameState.LOBBY -> {
                player.gameMode = org.bukkit.GameMode.ADVENTURE

                val lobbyWorld = org.bukkit.Bukkit.getWorlds()[0]
                player.teleport(lobbyWorld.spawnLocation)

                player.inventory.clear()
                player.foodLevel = 20
                player.health = (player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20) as Double

                for (effect in player.activePotionEffects) {
                    player.removePotionEffect(effect.type)
                }
            }

            GameState.IN_GAME -> {
                var myTeam = plugin.teamManager.getPlayerTeam(player)
                if (myTeam != TeamColor.NEUTRAL) {

                } else {
                    val redCount = plugin.teamManager.getAliveCount(TeamColor.RED) ?: 0
                    val blueCount = plugin.teamManager.getAliveCount(TeamColor.BLUE) ?: 0

                    val targetTeam = when {
                        redCount < blueCount -> TeamColor.RED
                        blueCount < redCount -> TeamColor.BLUE
                        else -> if (Math.random() > 0.5) TeamColor.RED else TeamColor.BLUE
                    }

                    // 分配队伍缓存
                    plugin.teamManager.playerTeams[player.uniqueId] = targetTeam
                    player.sendMessage("§a你中途加入战场，已分配至 ${targetTeam.colorCode}${targetTeam.displayName}§a！")

                    plugin.teamManager.setupPlayerTeam(player, targetTeam)

                    // 传送至本队大本营
                    val spawnLoc = org.bukkit.Location(
                        org.bukkit.Bukkit.getWorld(plugin.worldManager.gameWorldName),
                        0.0,
                        150.0,
                        0.0
                    )
                    val teamSpawn =
                        plugin.teamManager.teamsCapitals[targetTeam]?.clone()?.add(0.0, 3.0, 0.0) ?: spawnLoc
                    player.teleport(teamSpawn)
                    player.gameMode = GameMode.SURVIVAL

                    // 发放基础物资
                    player.inventory.clear()
                    player.inventory.addItem(org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 16))
                    player.inventory.addItem(org.bukkit.inventory.ItemStack(org.bukkit.Material.OAK_BOAT, 1))
                    player.inventory.addItem(org.bukkit.inventory.ItemStack(org.bukkit.Material.COMPASS, 1))
                    // 发放实时战场地图
                    plugin.mapManager.giveMapToPlayer(player)
                }
            }

            GameState.RESET -> {

                }
        }
    }

    @EventHandler
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        // 不在大厅直接放行
        if (plugin.stateManager.currentState != GameState.LOBBY) return
        val damager = event.damager
        val victim = event.entity
        // 玩家打玩家 → 取消伤害
        if (damager is Player && victim is Player) {
            event.isCancelled = true
            damager.sendMessage("§c大厅禁止PVP！")
        }
    }

    @EventHandler
    fun onPlayerSwapHandItems(event: org.bukkit.event.player.PlayerSwapHandItemsEvent) {
        val player = event.player
        if (plugin.stateManager.currentState != GameState.IN_GAME) return

        if (!player.isSneaking) return

        val currentBase = plugin.structurePlacer.getBaseAtPlayer(player.location) ?: return

        val myTeam = plugin.teamManager.getPlayerTeam(player)

        if (currentBase.ownerTeam == TeamColor.NEUTRAL || currentBase.ownerTeam != myTeam) {
            event.isCancelled = true

            val ownerTeam = currentBase.ownerTeam ?: TeamColor.NEUTRAL
            player.sendMessage("§c§l【访问拒绝】 §c这里是 ${ownerTeam.colorCode}${ownerTeam.displayName} §c的据点，你无法查看其战略菜单！")
            return
        }

        event.isCancelled = true

        val menuHolder = top.yurikale.landFight.ui.BaseMenuHolder(currentBase, plugin)
        menuHolder.setupMainMenu()

        player.openInventory(menuHolder.inventory)
        player.playSound(player.location, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.0f, 1.1f)
    }

    // 统管所有菜单的点击事件 (究极进化版)
    @EventHandler
    fun onMenuClick(event: org.bukkit.event.inventory.InventoryClickEvent) {
        val holder = event.inventory.holder

        // 只要点击的界面是我们自己写的 ActionMenu 基类
        if (holder is top.yurikale.landFight.ui.ActionMenu) {
            // 把事件全权移交给对应的界面自己处理
            holder.handleClick(event)
        }
    }
}