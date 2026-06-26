package top.yurikale.landFight

import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import top.yurikale.landFight.command.GameCommand
import top.yurikale.landFight.listener.GameListener
import top.yurikale.landFight.world.StructurePlacer
import top.yurikale.landFight.world.WorldManager
import top.yurikale.landFight.state.GameStateManager
import top.yurikale.landFight.state.NetworkGraph
import top.yurikale.landFight.system.IndustryManager
import top.yurikale.landFight.team.TeamManager
import top.yurikale.landFight.ui.MapManager
import top.yurikale.landFight.ui.SidebarManager

class LandFight : JavaPlugin() {

    lateinit var stateManager: GameStateManager
        private set
    lateinit var worldManager: WorldManager
        private set
    lateinit var structurePlacer: StructurePlacer
        private set
    lateinit var teamManager: TeamManager
        private set
    lateinit var sidebarManager: SidebarManager
        private set
    lateinit var mapManager: MapManager
        private set
    lateinit var  industryManager: IndustryManager
        private set
    lateinit var guardManager: top.yurikale.landFight.system.GuardManager // 【新增】
        private set
//    lateinit var networkGraph: NetworkGraph
//      private set

    var currentGameTask: top.yurikale.landFight.state.GameTask? = null
    var lobbyCountdownTask: org.bukkit.scheduler.BukkitRunnable? = null

    // 配置项
    val minStartPlayer = 2
    val lobbyWaitSecond = 30

    override fun onEnable() {
        // Plugin startup logic
        if (!dataFolder.exists()) dataFolder.mkdirs()

        saveResource("base_tower.nbt", false)

        stateManager = GameStateManager(this)
        worldManager = WorldManager(this)
        structurePlacer = StructurePlacer(this)
        teamManager = TeamManager(this)
        sidebarManager = SidebarManager(this)
        mapManager = MapManager(this)
        industryManager = IndustryManager(this)
        guardManager = top.yurikale.landFight.system.GuardManager(this)
//        networkGraph = NetworkGraph()

        server.pluginManager.registerEvents(GameListener(this), this)
        getCommand("lf")?.setExecutor(GameCommand(this))


        logger.info("领地战争 （LandFight） 已启动成功！")

        stateManager.switchState(top.yurikale.landFight.state.GameState.LOBBY)
        // TODO: register listeners and commands

    }

    override fun onDisable() {
        // Plugin shutdown logic
        currentGameTask?.cancel()
        lobbyCountdownTask?.cancel()
        lobbyCountdownTask = null
        worldManager.resetBattleWorld()
        structurePlacer.destroyProgressBar()
        mapManager.clearMap()
        logger.info("领地战争（LandFight）已安全关闭。")
    }
}
