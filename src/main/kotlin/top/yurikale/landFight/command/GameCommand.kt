package top.yurikale.landFight.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.CommandExecutor
import org.bukkit.entity.Player
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.GameState

class GameCommand(private val plugin: LandFight) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player || !sender.isOp) {
            sender.sendMessage("你没有权限！")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("用法：/lf <start|stop>")
            return true
        }

        when (args[0].lowercase()) {
            "start" -> {
                if (plugin.stateManager.currentState != GameState.LOBBY) {
                    sender.sendMessage("Game has already started!")
                    return true
                }
                Bukkit.broadcastMessage("管理员强制启动了游戏！")
                plugin.stateManager.switchState(GameState.IN_GAME)
            }
            "stop" -> {
                Bukkit.broadcastMessage("管理员强制停止了游戏！")
                plugin.stateManager.switchState(GameState.RESET)
            }
            "map" -> {
                if (plugin.stateManager.currentState != GameState.IN_GAME) {
                    sender.sendMessage("§c仅游戏进行中可获取战场地图！")
                    return true
                }
                val p = sender as Player
                plugin.mapManager.giveMapToPlayer(p)
                p.sendMessage("§a已补发战场据点地图！")
            }
            else -> sender.sendMessage("§e用法：/lf <start|stop|map>")
        }
        return true
    }
}