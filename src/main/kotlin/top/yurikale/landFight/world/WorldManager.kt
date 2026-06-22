package top.yurikale.landFight.world

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import top.yurikale.landFight.LandFight
import java.io.File
import kotlin.uuid.Uuid.Companion.random

class WorldManager(private val plugin: LandFight) {
    var gameWorldName = "landfight_battle_world"

    fun createBattleWorld(): World? {
        plugin.logger.info("Creating BATTLE world...")
        val random = random()
        gameWorldName = gameWorldName + random.toString()
        val creator = WorldCreator(gameWorldName)
        creator.environment(World.Environment.NORMAL)

        // THIS IS SYNC!
        val world = Bukkit.createWorld(creator)

        // set default rule
        world?.let {
            it.worldBorder.size = 1500.0
            it.keepSpawnInMemory = false
            plugin.logger.info("Battle world created.")
        }
        return world
    }

    fun resetBattleWorld() {
        val world = Bukkit.getWorld(gameWorldName)
        if (world != null) {
//            val lobbyWorld = Bukkit.getWorlds()[0]
//            for (player in world.players) {
//                player.teleport(lobbyWorld.spawnLocation)
//                player.sendMessage("游戏已结束，正在为您传送到大厅...")
//            }

            Bukkit.unloadWorld(world, false) // The second parameter set to false means "do not save data"
            plugin.logger.info("Battle world resetted.")

            val worldFolder = File(Bukkit.getWorldContainer(), gameWorldName)
            if (!worldFolder.exists()) {
                val success = worldFolder.deleteRecursively()
                if (success) {
                    plugin.logger.info("Battle world folder is deleted.")
                } else {
                    plugin.logger.warning("Failed to detele world folder!")
                }
            }
            gameWorldName = "landfight_battle_world"
        }
    }
}