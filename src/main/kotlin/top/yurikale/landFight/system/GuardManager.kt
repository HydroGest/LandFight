package top.yurikale.landFight.system

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Mob
import org.bukkit.entity.Pillager
import org.bukkit.entity.Player
import org.bukkit.entity.Vindicator
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ColorableArmorMeta
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base
import top.yurikale.landFight.team.TeamColor
import java.util.UUID

enum class GuardMode { FOLLOW, DEFEND }

data class GuardData(
    val uniqueId: UUID,
    val baseId: Int,
    val team: TeamColor,
    var mode: GuardMode = GuardMode.DEFEND,
    var followTarget: UUID? = null,
    var isTransitioning: Boolean = false
)

class GuardManager(private val plugin: LandFight) {

    val guards = mutableMapOf<UUID, GuardData>()

    companion object {
        private const val COOLDOWN_MS = 5 * 60 * 1000L
        private const val PEAK_PRICE = 30.0
        private const val BASE_PRICE = 10
    }

    init {
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                tickGuards()
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }

    fun calculateCost(base: Base): Int {
        val now = System.currentTimeMillis()
        val elapsed = now - base.lastGuardBuyTime
        if (elapsed >= COOLDOWN_MS) return BASE_PRICE

        val ratio = Math.exp(-elapsed.toDouble() / (COOLDOWN_MS / 5.0))
        val cost = BASE_PRICE + (PEAK_PRICE * ratio)
        return cost.toInt().coerceAtLeast(BASE_PRICE)
    }

    fun spawnGuards(base: Base, player: Player) {
        val loc = player.location
        val team = base.ownerTeam ?: return

        val vindicator = loc.world.spawn(loc, Vindicator::class.java)
        vindicator.isCustomNameVisible = true
        vindicator.isPatrolLeader = false
        vindicator.setRemoveWhenFarAway(false)
        equipGuard(vindicator, team, Material.IRON_AXE)

        val pillager = loc.world.spawn(loc, Pillager::class.java)
        pillager.isCustomNameVisible = true
        pillager.isPatrolLeader = false
        pillager.setRemoveWhenFarAway(false)
        equipGuard(pillager, team, Material.CROSSBOW)

        guards[vindicator.uniqueId] = GuardData(vindicator.uniqueId, base.id, team, GuardMode.DEFEND)
        guards[pillager.uniqueId] = GuardData(pillager.uniqueId, base.id, team, GuardMode.DEFEND)

        base.lastGuardBuyTime = System.currentTimeMillis()
    }

    private fun equipGuard(entity: Mob, team: TeamColor, weapon: Material) {
        val color = if (team == TeamColor.RED) Color.RED else Color.BLUE

        fun dyeArmor(mat: Material): ItemStack {
            val item = ItemStack(mat)
            val meta = item.itemMeta as ColorableArmorMeta
            meta.setColor(color)
            item.itemMeta = meta
            return item
        }

        entity.equipment?.let {
            it.setHelmet(dyeArmor(Material.LEATHER_HELMET))
            it.setChestplate(dyeArmor(Material.LEATHER_CHESTPLATE))
            it.setLeggings(dyeArmor(Material.LEATHER_LEGGINGS))
            it.setBoots(dyeArmor(Material.LEATHER_BOOTS))
            it.setItemInMainHand(ItemStack(weapon))

            it.setHelmetDropChance(0.0f)
            it.setChestplateDropChance(0.0f)
            it.setLeggingsDropChance(0.0f)
            it.setBootsDropChance(0.0f)
            it.setItemInMainHandDropChance(0.0f)
        }
    }

    private fun tickGuards() {
        val iterator = guards.entries.iterator()
        while (iterator.hasNext()) {
            val (entityId, data) = iterator.next()
            val entity = Bukkit.getEntity(entityId) as? Mob

            // 如果是 null，说明区块卸载了，跳过但不删除数据！
            if (entity == null) continue

            // 只有在确认实体死亡或被移除时才清理数据
            if (entity.isDead || !entity.isValid) {
                iterator.remove()
                continue
            }

            // 1. 主动检查并清除错误仇恨（防止底层 AI 卡顿锁定同队）
            val target = entity.target
            if (target != null) {
                var shouldClearTarget = false
                if (target is Player) {
                    val targetTeam = plugin.teamManager.getPlayerTeam(target)
                    if (targetTeam == data.team || targetTeam == TeamColor.NEUTRAL) {
                        shouldClearTarget = true
                    }
                } else if (target is Mob) {
                    val targetGuardData = guards[target.uniqueId]
                    if (targetGuardData == null || targetGuardData.team == data.team) {
                        shouldClearTarget = true
                    }
                } else {
                    shouldClearTarget = true
                }

                if (shouldClearTarget) {
                    entity.target = null
                }
            }

            // 2. 警报逻辑
            val base = plugin.structurePlacer.activeBases[data.baseId]
            val isNearBase = base != null && entity.location.distanceSquared(base.location) < 400
            val hasValidTarget = target != null && entity.target != null

            if (hasValidTarget && data.mode == GuardMode.DEFEND && isNearBase && base != null && !base.isAlerted) {
                base.isAlerted = true
                val targetName = if (target is Player) target.name else (target as Mob).customName ?: "敌方守卫"
                broadcastToTeam(data.team, "§c【警报】据点 #${data.baseId} 遭到 $targetName 攻击，守卫正在迎击！")
            } else if (!hasValidTarget && base != null && base.isAlerted) {
                base.isAlerted = false
            }

            // 3. 更新名称显示
            updateGuardName(entity, data)

            // 4. 无仇恨时的移动逻辑
            if (!hasValidTarget && !data.isTransitioning) {
                if (data.mode == GuardMode.FOLLOW) {
                    val targetPlayer = data.followTarget?.let { Bukkit.getPlayer(it) }
                    if (targetPlayer != null && targetPlayer.world == entity.world) {
                        if (entity.location.distanceSquared(targetPlayer.location) > 9.0) {
                            entity.pathfinder.moveTo(targetPlayer.location, 1.0)
                        }
                    }
                } else if (data.mode == GuardMode.DEFEND) {
                    if (base != null && entity.location.distanceSquared(base.location) > 9.0) {
                        entity.pathfinder.moveTo(base.location, 1.0)
                    }
                }
            }
        }
    }

    private fun updateGuardName(entity: Mob, data: GuardData) {
        val typeName = if (entity is Vindicator) "卫道士" else "掠夺者"
        val modeStr = when (data.mode) {
            GuardMode.FOLLOW -> {
                val name = data.followTarget?.let { Bukkit.getPlayer(it)?.name ?: "未知" }
                "跟随[$name]"
            }
            GuardMode.DEFEND -> "守卫据点"
        }
        entity.customName = "${data.team.colorCode}[守卫] $typeName #${data.baseId} §7| §f$modeStr"
    }

    private fun broadcastToTeam(team: TeamColor, msg: String) {
        for (player in Bukkit.getOnlinePlayers()) {
            if (plugin.teamManager.getPlayerTeam(player) == team) {
                player.sendMessage(msg)
            }
        }
    }

    fun handleGuardDeath(entity: Mob) {
        val data = guards.remove(entity.uniqueId) ?: return
        val typeName = if (entity is Vindicator) "卫道士" else "掠夺者"
        val loc = entity.location
        val coord = "${loc.blockX}, ${loc.blockY}, ${loc.blockZ}"
        broadcastToTeam(data.team, "§c【战损】己方据点 #${data.baseId} 的 $typeName §c在 ($coord) 阵亡了！")
    }

    fun setDefendMode(guardData: GuardData) {
        guardData.mode = GuardMode.DEFEND
        guardData.isTransitioning = true
        guardData.followTarget = null

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            guardData.isTransitioning = false
        }, 60L)
    }

    fun setFollowMode(guardData: GuardData, player: Player) {
        guardData.mode = GuardMode.FOLLOW
        guardData.followTarget = player.uniqueId
        guardData.isTransitioning = false
    }

    fun getGuardData(uniqueId: UUID): GuardData? {
        return guards[uniqueId]
    }
}