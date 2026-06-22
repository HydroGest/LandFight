package top.yurikale.landFight.listener

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import top.yurikale.landFight.state.GameState
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
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
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val clickedBlock = event.clickedBlock ?: return
        if (!Tag.WOOL.isTagged(clickedBlock.type)) return

        val clickedLocation = clickedBlock.location
        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL) return

        // 【v2 核心改造】：直接在 Map 的 values 里找对应的 Base 对象
        val clickedBase = plugin.structurePlacer.activeBases.values.find { base ->
            isSameBlockPos(base.location, clickedLocation)
        }

        // 如果找不到，说明点的只是一块普通的羊毛，直接 return
        if (clickedBase == null) return

        val myTeam = plugin.teamManager.getPlayerTeam(player)

        // 1. 如果当前据点不是我的，执行【占领逻辑】
        if (clickedBase.ownerTeam != myTeam) {
            val enemyTeam = clickedBase.ownerTeam

            // 如果这个据点恰好是敌方的大本营（后续这里可以改成判断 Base 对象自身是不是大本营）
            if (enemyTeam != TeamColor.NEUTRAL && plugin.teamManager.teamsCapitals[enemyTeam]?.let { isSameBlockPos(it, clickedLocation) } == true) {
                plugin.teamManager.teamsCapitals.remove(enemyTeam)
                enemyTeam?.let { org.bukkit.Bukkit.broadcastMessage("§c【重大战报】${it.colorCode}${enemyTeam.displayName}§c 大本营被 §f${player.name}§c 攻破！敌方全体无法复活！") }
            }

            // 对象属性易主
            clickedBase.ownerTeam = myTeam

            // 【预留给图论的接口】：因为据点易主，之前跟它连接的交通线全部作废！
            plugin.structurePlacer.networkGraph.clearConnectionsOf(clickedBase.id)

            org.bukkit.Bukkit.broadcastMessage("§e【据点占领】${myTeam.colorCode}${player.name}§e 成功占领据点 (${clickedLocation.blockX}, ${clickedLocation.blockY}, ${clickedLocation.blockZ})！")
        }
        // 2. 如果是自己的据点，且玩家在潜行，执行【转移大本营逻辑】
        else if (player.isSneaking) {
            val currentCapital = plugin.teamManager.teamsCapitals[myTeam]
            if (currentCapital == null || !isSameBlockPos(currentCapital, clickedLocation)) {
                plugin.teamManager.teamsCapitals[myTeam] = clickedLocation
                player.sendMessage("§a【大本营】§f你已为本队更换新大本营！")
                org.bukkit.Bukkit.broadcastMessage("§e【战略转移】§f${player.name} 为 ${myTeam.colorCode}${myTeam.displayName}§e 设立全新大本营！")
            }
        }

        // 3. 视觉反馈更新
        when(myTeam){
            TeamColor.RED -> {
                clickedBlock.type = Material.RED_WOOL
                clickedLocation.clone().add(0.0, 13.0, 0.0).block.type = Material.RED_STAINED_GLASS
            }
            TeamColor.BLUE -> {
                clickedBlock.type = Material.BLUE_WOOL
                clickedLocation.clone().add(0.0, 13.0, 0.0).block.type = Material.BLUE_STAINED_GLASS
            }
            TeamColor.NEUTRAL -> {
                clickedBlock.type = Material.GRAY_WOOL
                clickedLocation.clone().add(0.0, 13.0, 0.0).block.type = Material.AIR
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val posKey = "${block.x},${block.y},${block.z}"

        // 是系统生成的据点原生建筑 → 禁止破坏
        if (plugin.structurePlacer.allBaseStructureBlocks.contains(posKey)) {
            event.isCancelled = true
            player.sendMessage("§c据点原生建筑不可破坏，你自己放置的方块可以拆除！")
            return
        }

        // 保护占领羊毛核心
        if (Tag.WOOL.isTagged(block.type)) {
            // 【v2 核心改造】：用同样的找对象方法防破坏
            val isBaseCore = plugin.structurePlacer.activeBases.values.any { base ->
                isSameBlockPos(base.location, block.location)
            }

            if (isBaseCore) {
                event.isCancelled = true
                player.sendMessage("§c据点核心无法挖掘，请右键操作！")
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
}