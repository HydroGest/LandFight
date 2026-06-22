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
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (plugin.stateManager.currentState != GameState.IN_GAME) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val clickedBlock = event.clickedBlock ?: return
        if (!Tag.WOOL.isTagged(clickedBlock.type)) return
        val clickedLocation = clickedBlock.location
        plugin.logger.info("Clicked:${plugin.structurePlacer.location2String(clickedLocation)}")
        if (plugin.structurePlacer.activeBases.containsKey(plugin.structurePlacer.location2String(clickedLocation))) {
            val player = event.player
            if (player.gameMode != GameMode.SURVIVAL) return
            val currentOwner = plugin.structurePlacer.activeBases[plugin.structurePlacer.location2String(clickedLocation)]

            val myTeam = plugin.teamManager.getPlayerTeam(player)

            if (currentOwner != myTeam.name) {
                val enemyTeam = if (myTeam == TeamColor.RED) TeamColor.BLUE else TeamColor.RED
                if (plugin.teamManager.teamsCapitals[enemyTeam] == clickedLocation) {
                    plugin.teamManager.teamsCapitals.remove(enemyTeam)
                    org.bukkit.Bukkit.broadcastMessage("§c【重大战报】${enemyTeam.colorCode}${enemyTeam.displayName}§c 大本营被 §f${player.name}§c 攻破！敌方全体无法复活！")
                }

                plugin.structurePlacer.activeBases[plugin.structurePlacer.location2String(clickedLocation)] = myTeam.name
                org.bukkit.Bukkit.broadcastMessage("§e【据点占领】${myTeam.colorCode}${player.name}§e 成功占领据点 (${clickedLocation.blockX}, ${clickedLocation.blockY}, ${clickedLocation.blockZ})！")
            } else if (player.isSneaking) {
                val currentCapital = plugin.teamManager.teamsCapitals[myTeam]
                if (currentCapital != clickedLocation) {
                    plugin.teamManager.teamsCapitals[myTeam] = clickedLocation
                    player.sendMessage("§a【大本营】§f你已为本队更换新大本营！")
                    org.bukkit.Bukkit.broadcastMessage("§e【战略转移】§f${player.name} 为 ${myTeam.colorCode}${myTeam.displayName}§e 设立全新大本营！")

                }
            }

            // 羊毛颜色切换
            when(myTeam){
                TeamColor.RED -> clickedBlock.type = org.bukkit.Material.RED_WOOL
                TeamColor.BLUE -> clickedBlock.type = org.bukkit.Material.BLUE_WOOL
                TeamColor.NEUTRAL -> clickedBlock.type = org.bukkit.Material.GRAY_WOOL
            }

            // Y+13位置放置对应彩色玻璃，中立无玻璃（空气）
            val glassLoc = clickedLocation.clone().add(0.0, 13.0, 0.0)
            val glassBlock = glassLoc.block
            when(myTeam){
                TeamColor.RED -> glassBlock.type = org.bukkit.Material.RED_STAINED_GLASS
                TeamColor.BLUE -> glassBlock.type = org.bukkit.Material.BLUE_STAINED_GLASS
                TeamColor.NEUTRAL -> glassBlock.type = org.bukkit.Material.AIR
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
            val locStr = plugin.structurePlacer.location2String(block.location)
            if (plugin.structurePlacer.activeBases.containsKey(locStr)) {
                event.isCancelled = true
                player.sendMessage("§c据点占领羊毛无法挖掘，请右键操作！")
                return
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