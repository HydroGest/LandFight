package top.yurikale.landFight.system

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Mob
import org.bukkit.entity.Pillager
import org.bukkit.entity.Player
import org.bukkit.entity.Sheep
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
    var isTransitioning: Boolean = false,
    var nextPatrolTime: Long = 0L,
    var nextRetargetTime: Long = 0L
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

            if (entity == null) continue

            if (entity.isDead || !entity.isValid) {
                iterator.remove()
                continue
            }

            // 1. 仇恨净化与超距脱离
            val originalTarget = entity.target
            if (originalTarget != null) {
                var shouldClearTarget = false
                if (originalTarget is Player) {
                    val targetTeam = plugin.teamManager.getPlayerTeam(originalTarget)
                    if (targetTeam == data.team || targetTeam == TeamColor.NEUTRAL || originalTarget.gameMode != org.bukkit.GameMode.SURVIVAL) {
                        shouldClearTarget = true
                    }
                } else if (originalTarget is Mob) {
                    if (originalTarget is Sheep) {
                        shouldClearTarget = true
                    } else {
                        val targetGuardData = guards[originalTarget.uniqueId]
                        if (targetGuardData == null || targetGuardData.team == data.team) {
                            shouldClearTarget = true
                        }
                    }
                } else {
                    shouldClearTarget = true
                }
                // 新增：如果目标跑出40格范围，强制放弃追击，防止被风筝
                if (originalTarget.isDead || !originalTarget.isValid ||
                    (originalTarget.world == entity.world && entity.location.distanceSquared(originalTarget.location) > 1600.0)) {
                    shouldClearTarget = true
                }
                if (shouldClearTarget) {
                    entity.target = null
                }
            }
            // 1.5 智能动态索敌与目标重评估
            // 每2~3秒重新审视战场，如果当前目标被太多人围殴，则切换目标
            val needRetarget = entity.target == null || System.currentTimeMillis() >= data.nextRetargetTime
            if (needRetarget && !data.isTransitioning) {
                val newTarget = findOptimalTarget(entity, data)
                // 如果找到了新目标，且与当前目标不同，则切换
                if (newTarget != null && newTarget.uniqueId != entity.target?.uniqueId) {
                    entity.target = newTarget
                }
                // 设置 2~3 秒后再次重评估
                data.nextRetargetTime = System.currentTimeMillis() + 2000L + (Math.random() * 1000L).toLong()
            }

            // 2. 警报逻辑（使用清除/索敌后的当前目标）
            val currentTarget = entity.target
            val hasValidTarget = currentTarget != null
            val base = plugin.structurePlacer.activeBases[data.baseId]
            val isNearBase = base != null && entity.location.distanceSquared(base.location) < 400

            if (hasValidTarget && data.mode == GuardMode.DEFEND && isNearBase && base != null && !base.isAlerted) {
                base.isAlerted = true
                val targetName = if (currentTarget is Player) currentTarget.name
                else (currentTarget as? Mob)?.customName ?: "敌方守卫"
                broadcastToTeam(data.team, "§c【警报】据点 #${data.baseId} 遭到 $targetName 攻击，守卫正在迎击！")
            } else if (!hasValidTarget && base != null && base.isAlerted) {
                base.isAlerted = false
            }

            // 3. 更新名称显示
            updateGuardName(entity, data)

            // 4. 无仇恨时的移动逻辑（三段式距离判定 + 巡逻机制）
            if (!hasValidTarget && !data.isTransitioning) {
                if (data.mode == GuardMode.FOLLOW) {
                    val targetPlayer = data.followTarget?.let { Bukkit.getPlayer(it) }
                    if (targetPlayer != null && targetPlayer.world == entity.world) {
                        val distSq = entity.location.distanceSquared(targetPlayer.location)
                        when {
                            // 距离 > 50格：直接传送到玩家身后
                            distSq > 2500.0 -> {
                                val loc = targetPlayer.location.clone().subtract(targetPlayer.location.direction.multiply(2))
                                entity.teleport(loc)
                            }
                            // 距离 > 10格：快速跑向玩家
                            distSq > 100.0 -> {
                                entity.pathfinder.moveTo(targetPlayer.location, 1.5)
                            }
                            // 距离 <= 10格：在玩家附近随机巡逻，不再紧贴
                            else -> {
                                if (System.currentTimeMillis() >= data.nextPatrolTime) {
                                    val angle = Math.random() * Math.PI * 2
                                    val radius = 3.0 + Math.random() * 3.0 // 在3~6格半径内随机走动
                                    val patrolLoc = targetPlayer.location.clone().add(
                                        Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius
                                    )
                                    entity.pathfinder.moveTo(patrolLoc, 1.0)
                                    // 3~6秒后再次巡逻
                                    data.nextPatrolTime = System.currentTimeMillis() + (3000 + Math.random() * 3000).toLong()
                                }
                            }
                        }
                    }
                } else if (data.mode == GuardMode.DEFEND) {
                    if (base != null && entity.world == base.location.world) {
                        val distSq = entity.location.distanceSquared(base.location)
                        when {
                            // 距离 > 50格：直接传送回据点
                            distSq > 2500.0 -> {
                                entity.teleport(base.location.clone().add(0.0, 1.0, 0.0))
                            }
                            // 距离 > 10格：快速跑回据点
                            distSq > 100.0 -> {
                                entity.pathfinder.moveTo(base.location, 1.5)
                            }
                            // 距离 <= 10格：在据点附近随机巡逻
                            else -> {
                                if (System.currentTimeMillis() >= data.nextPatrolTime) {
                                    val angle = Math.random() * Math.PI * 2
                                    val radius = 3.0 + Math.random() * 5.0 // 在3~8格半径内随机走动
                                    val patrolLoc = base.location.clone().add(
                                        Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius
                                    )
                                    entity.pathfinder.moveTo(patrolLoc, 1.0)
                                    // 4~8秒后再次巡逻
                                    data.nextPatrolTime = System.currentTimeMillis() + (4000 + Math.random() * 4000).toLong()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 智能目标评估算法
     * 评分维度：距离、围攻惩罚、残血加成、职业克制
     */
    private fun findOptimalTarget(entity: Mob, data: GuardData): org.bukkit.entity.LivingEntity? {
        val detectRange = 16.0
        val nearbyEntities = entity.getNearbyEntities(detectRange, 8.0, detectRange)
        // 收集候选敌人
        val enemies = mutableListOf<org.bukkit.entity.LivingEntity>()
        for (nearby in nearbyEntities) {
            if (nearby !is org.bukkit.entity.LivingEntity) continue
            if (nearby.isDead || !nearby.isValid) continue
            if (nearby.world != entity.world) continue
            val isEnemy = when (nearby) {
                is Player -> {
                    nearby.gameMode == org.bukkit.GameMode.SURVIVAL &&
                            plugin.teamManager.getPlayerTeam(nearby).let {
                                it != data.team && it != TeamColor.NEUTRAL
                            }
                }
                is Mob -> {
                    nearby !is Sheep &&
                            guards[nearby.uniqueId]?.let { it.team != data.team } == true
                }
                else -> false
            }
            if (isEnemy) enemies.add(nearby)
        }
        if (enemies.isEmpty()) return null
        // 统计附近友军正在攻击的目标数量（用于避免过度集火）
        val focusCount = mutableMapOf<UUID, Int>()
        for (nearby in nearbyEntities) {
            if (nearby is Mob && nearby !is Sheep) {
                val allyData = guards[nearby.uniqueId] ?: continue
                if (allyData.team == data.team) {
                    val allyTarget = nearby.target ?: continue
                    if (enemies.any { it.uniqueId == allyTarget.uniqueId }) {
                        focusCount[allyTarget.uniqueId] = (focusCount[allyTarget.uniqueId] ?: 0) + 1
                    }
                }
            }
        }
        var bestTarget: org.bukkit.entity.LivingEntity? = null
        var bestScore = Double.MIN_VALUE
        for (enemy in enemies) {
            val distSq = entity.location.distanceSquared(enemy.location)
            // 1. 距离得分：越近分越高 (0~100分)
            val distScore = 100.0 - (distSq / (detectRange * detectRange) * 100.0)
            // 2. 围攻惩罚：若已被2个以上友军攻击，每多1人扣30分，强力分散火力
            val count = focusCount[enemy.uniqueId] ?: 0
            val focusPenalty = if (count >= 2) (count - 1) * 30.0 else 0.0
            // 3. 残血加成：血量越低越想杀，最高加25分
            val maxHealth = enemy.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
            val healthScore = (1.0 - (enemy.health / maxHealth)).coerceIn(0.0, 1.0) * 25.0
            // 4. 职业与行为加成
            var roleBonus = 0.0
            // 弩兵(掠夺者)偏好攻击敌方弩兵，进行远程对射
            if (entity is Pillager && enemy is Pillager) roleBonus += 20.0
            // 优先攻击正在攻击我方玩家的敌人
            if (enemy is Mob) {
                val enemyTarget = enemy.target
                if (enemyTarget is Player && plugin.teamManager.getPlayerTeam(enemyTarget) == data.team) {
                    roleBonus += 30.0
                }
            }
            val totalScore = distScore - focusPenalty + healthScore + roleBonus
            if (totalScore > bestScore) {
                bestScore = totalScore
                bestTarget = enemy
            }
        }
        return bestTarget
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

    fun setFollowModeBatch(sourceGuardData: GuardData, player: Player): Int {
        val sourceEntity = Bukkit.getEntity(sourceGuardData.uniqueId) as? Mob ?: return 0
        var count = 0
        for ((_, data) in guards) {
            if (data.team != sourceGuardData.team) continue
            val entity = Bukkit.getEntity(data.uniqueId) as? Mob ?: continue
            if (entity.world == sourceEntity.world && entity.location.distanceSquared(sourceEntity.location) <= 25.0) { // 5^2 = 25
                setFollowMode(data, player)
                count++
            }
        }
        return count
    }

    fun setDefendModeBatch(sourceGuardData: GuardData): Int {
        val sourceEntity = Bukkit.getEntity(sourceGuardData.uniqueId) as? Mob ?: return 0
        var count = 0
        for ((_, data) in guards) {
            if (data.team != sourceGuardData.team) continue
            val entity = Bukkit.getEntity(data.uniqueId) as? Mob ?: continue
            if (entity.world == sourceEntity.world && entity.location.distanceSquared(sourceEntity.location) <= 25.0) {
                setDefendMode(data)
                count++
            }
        }
        return count
    }

    fun getGuardData(uniqueId: UUID): GuardData? {
        return guards[uniqueId]
    }
}