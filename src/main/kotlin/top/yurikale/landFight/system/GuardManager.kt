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
    var mode: GuardMode = GuardMode.FOLLOW,
    var followTarget: UUID? = null,
    var isTransitioning: Boolean = false // 是否在等待3秒延时
)

class GuardManager(private val plugin: LandFight) {

    val guards = mutableMapOf<UUID, GuardData>()

    companion object {
        private const val COOLDOWN_MS = 10 * 60 * 1000L // 10分钟
        private const val PEAK_PRICE = 300.0
        private const val BASE_PRICE = 20
    }

    init {
        // 独立循环：每 10 tick (0.5秒) 更新一次守卫行为
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                tickGuards()
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }

    /**
     * 计算当前招募价格（指数回落算法）
     */
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

        // 生成卫道士
        val vindicator = loc.world.spawn(loc, Vindicator::class.java)
        vindicator.isCustomNameVisible = true
        vindicator.customName = "${team.colorCode}[守卫] 卫道士 #${base.id}"
        vindicator.isPatrolLeader = false
        equipGuard(vindicator, team, Material.IRON_AXE)

        // 生成掠夺者
        val pillager = loc.world.spawn(loc, Pillager::class.java)
        pillager.isCustomNameVisible = true
        pillager.customName = "${team.colorCode}[守卫] 掠夺者 #${base.id}"
        pillager.isPatrolLeader = false
        equipGuard(pillager, team, Material.CROSSBOW)

        guards[vindicator.uniqueId] = GuardData(vindicator.uniqueId, base.id, team, GuardMode.FOLLOW, player.uniqueId)
        guards[pillager.uniqueId] = GuardData(pillager.uniqueId, base.id, team, GuardMode.FOLLOW, player.uniqueId)

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
            val entity = Bukkit.getEntity(entityId) as? Mob ?: run {
                iterator.remove()
                continue
            }
            if (entity.isDead) {
                iterator.remove()
                continue
            }

            // 1. 检查目标有效性
            val target = entity.target
            if (target is Player) {
                val targetTeam = plugin.teamManager.getPlayerTeam(target)
                if (targetTeam == data.team || targetTeam == TeamColor.NEUTRAL) {
                    entity.target = null // 清除无效仇恨
                } else {
                    return // 正在追击敌对玩家，不执行巡逻
                }
            } else if (target != null && target !is Player) {
                entity.target = null // 清除非玩家仇恨
            }

            // 2. 无仇恨时的移动逻辑
            if (data.isTransitioning) continue // 等待延时中

            if (data.mode == GuardMode.FOLLOW) {
                val targetPlayer = data.followTarget?.let { Bukkit.getPlayer(it) }
                if (targetPlayer != null && targetPlayer.world == entity.world) {
                    // 【修复】距离平方 9.0 代表 3m，大于 3m 才移动
                    if (entity.location.distanceSquared(targetPlayer.location) > 9.0) {
                        // 【修复】速度降至 1.0
                        entity.pathfinder.moveTo(targetPlayer.location, 1.0)
                    }
                }
            } else if (data.mode == GuardMode.DEFEND) {
                val base = plugin.structurePlacer.activeBases[data.baseId] ?: continue
                if (entity.location.distanceSquared(base.location) > 9.0) {
                    entity.pathfinder.moveTo(base.location, 1.0)
                }
            }
        }
    }

    fun setDefendMode(guardData: GuardData) {
        guardData.mode = GuardMode.DEFEND
        guardData.isTransitioning = true

        // 3秒延时
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            guardData.isTransitioning = false
        }, 60L)
    }

    fun setFollowMode(guardData: GuardData, player: Player) {
        guardData.mode = GuardMode.FOLLOW
        guardData.followTarget = player.uniqueId
        guardData.isTransitioning = false
    }

    // 获取守卫数据
    fun getGuardData(uniqueId: UUID): GuardData? {
        return guards[uniqueId]
    }
}